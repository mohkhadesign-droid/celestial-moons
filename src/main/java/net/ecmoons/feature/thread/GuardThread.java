package net.ecmoons.feature.thread;

import net.ecmoons.config.Settings;
import net.ecmoons.feature.ThreadFeatures;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Guard Thread and Last Stitch.
 * <p>
 * Guard: a player CHOOSES one directly connected teammate to protect (Guard key while aiming at them; again to clear).
 * One guardian per target, one target per guardian, and no chains or cycles: someone being guarded cannot guard, and
 * someone guarding cannot be guarded. The selection only does anything while the two are directly connected.
 * <p>
 * Damage split (LivingDamageEvent, lowest priority - after armour, Resistance, enchantments, absorption and the passive
 * Thread reduction, i.e. on the health the hit would really remove): for a permitted combat hit, share x loss (20%),
 * at most maxPerHit (2) and never taking the guardian below the floor (6), moves to the guardian. The guardian pays it
 * as a bounded internal health transaction - no second armour, absorption or Thread reduction, no recursion into any
 * Thread rule, no retaliation hooks - and the target's loss is reduced by exactly what was actually paid, so the total
 * health lost by the two is unchanged. A hit that was cancelled never reaches this event, so it transfers nothing.
 * <p>
 * Last Stitch (LivingDeathEvent, lowest priority - after totems and other death-prevention mods had their chance; a
 * death they already prevented never reaches it): if the guard has been continuously active for 3 s, the hit is
 * permitted combat damage, the guardian can pay 4 and stay at 6 or more, and neither has spent their Last Stitch in
 * this event, the death is cancelled, the target is left at 1 health and the guardian pays 4 - both or neither. Both
 * are marked as spent for this event (saved with the world), the guard snaps and both get a 15 s guard cooldown.
 */
public final class GuardThread {
    static final class Guard {
        final UUID target;
        final int targetId;
        long since = -1;      // game time the current uninterrupted active connection began (-1 = inactive)
        boolean paidOnce;

        Guard(ServerPlayerEntity t) {
            target = t.getUUID();
            targetId = t.getId();
        }
    }

    private static final Map<UUID, Guard> GUARDS = new HashMap<>();   // guardian -> guard
    private static final Map<UUID, UUID> GUARDED = new HashMap<>();   // target -> guardian
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
    /** Tests: amounts of the last split (target loss before, guardian paid, target loss after). */
    public static float lastBefore, lastPaid, lastAfter;
    public static int rescues;

    private GuardThread() {}

    static Settings s() {
        return Settings.server();
    }

    /** Guard key: select / switch / clear (same target or -1). Returns a message for the player. */
    public static String toggle(ServerPlayerEntity g, int targetId, long now) {
        Guard cur = GUARDS.get(g.getUUID());
        if (cur != null && (targetId < 0 || cur.targetId == targetId)) {
            clear(g.getUUID());
            return "Guard Thread cleared";
        }
        if (targetId < 0) return "Aim at a connected teammate to guard them";
        if (!s().b("thread_guard.enabled")) return "Guard Thread is disabled";
        if (!ThreadLinks.moonUp(g) || !ThreadLinks.eligible(g)) return "You can't use that now";
        Long cd = COOLDOWN.get(g.getUUID());
        if (cd != null && cd > now) return "Guard Thread recovers in " + (cd - now + 19) / 20 + "s";
        ServerPlayerEntity t = Lifeline.player(g, targetId);
        if (t == null || !ThreadLinks.direct(g, t)) return "Aim at a connected teammate to guard them";
        if (GUARDED.containsKey(g.getUUID())) return "You are being guarded - you can't guard someone else";
        if (GUARDS.containsKey(t.getUUID())) return t.getScoreboardName() + " is guarding someone - no guard chains";
        UUID other = GUARDED.get(t.getUUID());
        if (other != null && !other.equals(g.getUUID())) return t.getScoreboardName() + " already has a guardian";
        Long tcd = COOLDOWN.get(t.getUUID());
        if (tcd != null && tcd > now) return t.getScoreboardName() + " can't be guarded for " + (tcd - now + 19) / 20 + "s";
        clear(g.getUUID());
        Guard gd = new Guard(t);
        gd.since = now;
        GUARDS.put(g.getUUID(), gd);
        GUARDED.put(t.getUUID(), g.getUUID());
        return null;
    }

    public static void clear(UUID guardian) {
        Guard gd = GUARDS.remove(guardian);
        if (gd != null) GUARDED.remove(gd.target);
    }

    /** At the connection interval: active state, team changes, eligibility. */
    public static void tick(Iterable<ServerPlayerEntity> players, long now) {
        if (GUARDS.isEmpty()) return;
        for (ServerPlayerEntity g : players) {
            Guard gd = GUARDS.get(g.getUUID());
            if (gd == null) continue;
            ServerPlayerEntity t = Lifeline.player(g, gd.targetId);
            if (t == null || !ThreadLinks.eligible(g) || !ThreadLinks.teammates(g, t)) { // gone, dead, team changed
                clear(g.getUUID());
                continue;
            }
            boolean active = ThreadLinks.direct(g, t);
            if (!active) gd.since = -1;
            else if (gd.since < 0) gd.since = now;
        }
    }

    @Nullable
    static ServerPlayerEntity guardianOf(ServerPlayerEntity t) {
        UUID g = GUARDED.get(t.getUUID());
        if (g == null) return null;
        ServerPlayerEntity p = t.server.getPlayerList().getPlayer(g);
        if (p == null) p = Lifeline.TEST_PLAYERS.values().stream().filter(x -> x.getUUID().equals(g)).findFirst().orElse(null);
        return p;
    }

    /** Combat damage that Thread abilities may act on: from an attacker (melee or projectile), not an excluded type. */
    public static boolean combat(DamageSource src) {
        if (src == null || src.getEntity() == null || src.isBypassInvul()) return false;
        if (ThreadFeatures.excluded(src)) return false;
        return !s().list("thread_guard.excludedDamageTypes").contains(src.getMsgId());
    }

    /**
     * The split, given the health the hit is about to remove from the target. Returns the loss the target keeps.
     * The guardian's payment happens here, as an internal transaction.
     */
    public static float split(ServerPlayerEntity t, float loss, DamageSource src, long now) {
        if (loss <= 0f || ThreadLinks.internal() || !s().b("thread_guard.enabled") || !combat(src)) return loss;
        ServerPlayerEntity g = guardianOf(t);
        if (g == null || !ThreadLinks.direct(g, t)) return loss; // an inactive selection does nothing
        float floor = (float) s().d("thread_guard.guardianFloor");
        float want = Math.min(loss * (float) s().d("thread_guard.share"), (float) s().d("thread_guard.maxPerHit"));
        float can = Math.min(want, g.getHealth() - floor);
        if (can <= 0.001f) return loss;
        float paid = ThreadLinks.pay(g, can);          // what was REALLY taken (another mod could refuse)
        paid = Math.max(0f, Math.min(paid, can));
        lastBefore = loss;
        lastPaid = paid;
        lastAfter = loss - paid;
        if (paid > 0f) {
            ThreadNet.fxTeam(g, ThreadNet.FX_GUARD, t.getId(), g.getId(), paid);
            Guard gd = GUARDS.get(g.getUUID());
            if (gd != null && !gd.paidOnce) {
                gd.paidOnce = true;
                ThreadReactions.request(g.getLevel(), ThreadReactions.GUARD);
            }
        }
        return loss - paid;
    }

    /** True when the player holds a Totem of Undying (vanilla checks it before any death event; never consume both). */
    static boolean holdsTotem(ServerPlayerEntity p) {
        for (Hand h : Hand.values()) if (p.getItemInHand(h).getItem() == Items.TOTEM_OF_UNDYING) return true;
        return false;
    }

    /** Last Stitch. Returns true when the death is prevented (the caller cancels the death event). */
    public static boolean lastStitch(ServerPlayerEntity t, DamageSource src, long now) {
        if (!s().b("thread_laststitch.enabled") || ThreadLinks.internal() || !combat(src) || holdsTotem(t)) return false;
        if (s().list("thread_laststitch.excludedDamageTypes").contains(src.getMsgId())) return false;
        ServerPlayerEntity g = guardianOf(t);
        if (g == null) return false;
        Guard gd = GUARDS.get(g.getUUID());
        if (gd == null || gd.since < 0 || now - gd.since < s().d("thread_laststitch.establishSeconds") * 20) return false;
        if (!ThreadLinks.direct(g, t)) return false;
        ThreadEvent ev = ThreadEvent.get(t.getLevel());
        if (ev.spent(t.getUUID()) || ev.spent(g.getUUID())) return false;
        float cost = (float) s().d("thread_laststitch.guardianCost");
        if (g.getHealth() - cost < s().d("thread_laststitch.guardianFloor") - 1e-4) return false;
        // commit both or neither
        float before = g.getHealth();
        float paid = ThreadLinks.pay(g, cost);
        if (Math.abs(paid - cost) > 1e-3f) {
            g.setHealth(before);
            return false;
        }
        ThreadLinks.internal++;
        try {
            t.setHealth((float) s().d("thread_laststitch.saveHealth"));
        } finally {
            ThreadLinks.internal--;
        }
        ev.spend(t.getUUID(), g.getUUID());
        clear(g.getUUID());
        long cd = now + Math.round(s().d("thread_laststitch.guardCooldownSeconds") * 20);
        COOLDOWN.put(g.getUUID(), cd);
        COOLDOWN.put(t.getUUID(), cd);
        rescues++;
        ThreadNet.fxTeam(g, ThreadNet.FX_LAST_STITCH, g.getId(), t.getId(), cost);
        ThreadNet.notice(t, "Last Stitch used - " + g.getScoreboardName() + " paid two hearts");
        ThreadNet.notice(g, "Last Stitch used - you saved " + t.getScoreboardName());
        ThreadReactions.request(t.getLevel(), ThreadReactions.LAST_STITCH);
        return true;
    }

    // ---- state for the HUD and packets ---------------------------------------------------------------------------

    @Nullable
    static Guard guardOf(ServerPlayerEntity g) {
        return GUARDS.get(g.getUUID());
    }

    static Map<UUID, Guard> all() {
        return GUARDS;
    }

    public static boolean guarding(ServerPlayerEntity g, ServerPlayerEntity t) {
        Guard gd = GUARDS.get(g.getUUID());
        return gd != null && gd.target.equals(t.getUUID());
    }

    public static boolean established(ServerPlayerEntity g, long now) {
        Guard gd = GUARDS.get(g.getUUID());
        return gd != null && gd.since >= 0 && now - gd.since >= s().d("thread_laststitch.establishSeconds") * 20;
    }

    static int cooldown(ServerPlayerEntity p, long now) {
        Long c = COOLDOWN.get(p.getUUID());
        return c == null ? 0 : (int) Math.max(0, c - now);
    }

    /** Death, logout, dimension change: the player's selections end (guarding and being guarded). */
    public static void forget(UUID id) {
        clear(id);
        UUID g = GUARDED.remove(id);
        if (g != null) GUARDS.remove(g);
    }

    public static void endAll() {
        GUARDS.clear();
        GUARDED.clear();
    }

    public static void clearAll() {
        endAll();
        COOLDOWN.clear();
    }
}
