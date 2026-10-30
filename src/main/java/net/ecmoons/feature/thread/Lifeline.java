package net.ecmoons.feature.thread;

import net.ecmoons.config.Settings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.vector.Vector3d;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Lifeline: crouch + hold the Moon Ability key while aiming at an injured, directly connected teammate within 4 blocks.
 * After one second of steady aim, one health point a second moves from the donor to the recipient, at most four per
 * activation. The donor pays exactly what the recipient ACTUALLY gained (healing that another mechanic cancelled,
 * reduced or that would over-heal is never paid for, and a healing multiplier can never yield more than the donor
 * pays), never drops below the donor floor (6) and never spends absorption. It is a resource transfer, not damage: no
 * armour, knockback, hurt time, retaliation, Guard Thread or Last Stitch is involved.
 * <p>
 * Cancelled by releasing the key, looking away, leaving range or line of sight, the connection breaking, the donor
 * taking real damage, attacking, using an item or being teleported. One donor per recipient. The 10 s cooldown starts
 * when an activation that actually moved health ends; a cancel before any transfer costs nothing.
 */
public final class Lifeline {
    static final class Channel {
        final UUID recipient;
        final int recipientId;
        final long start;
        long lastTransfer = -1;
        float moved;
        boolean reacted;
        Vector3d lastPos;

        Channel(ServerPlayerEntity r, long start, Vector3d pos) {
            this.recipient = r.getUUID();
            this.recipientId = r.getId();
            this.start = start;
            this.lastPos = pos;
        }
    }

    private static final Map<UUID, Channel> CHANNELS = new HashMap<>();   // donor -> channel
    private static final Map<UUID, UUID> RECIPIENTS = new HashMap<>();    // recipient -> donor
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
    /** Self test: fake recipients are not in the world's entity list. */
    public static final Map<Integer, ServerPlayerEntity> TEST_PLAYERS = new HashMap<>();
    /** Total health moved since start (tests). */
    public static float totalMoved;
    public static String lastEnd = "";

    private Lifeline() {}

    static Settings s() {
        return Settings.server();
    }

    @Nullable
    static ServerPlayerEntity player(ServerPlayerEntity ref, int id) {
        Entity e = ref.getLevel().getEntity(id);
        if (e == null) e = TEST_PLAYERS.get(id);
        return e instanceof ServerPlayerEntity ? (ServerPlayerEntity) e : null;
    }

    /** Key down while crouching and aiming at a teammate. Returns null when the channel started, else the reason. */
    @Nullable
    public static String start(ServerPlayerEntity donor, int targetId, long now) {
        if (!s().b("thread_lifeline.enabled")) return "Lifeline is disabled";
        if (!ThreadLinks.moonUp(donor) || !ThreadLinks.eligible(donor)) return "You can't use that now";
        if (cooldown(donor, now) > 0) return "Lifeline recovers in " + (cooldown(donor, now) + 19) / 20 + "s";
        if (Knotbind.victim(donor)) return "Unravel the knot first";
        ServerPlayerEntity r = player(donor, targetId);
        if (r == null || !ThreadLinks.direct(donor, r)) return "Aim at a connected teammate";
        double range = s().d("thread_lifeline.range");
        if (donor.distanceToSqr(r) > range * range) return "Too far for a Lifeline";
        if (!ThreadLinks.aimingAt(donor, r, range, 0.4)) return "Aim at your teammate";
        if (r.getHealth() >= r.getMaxHealth() - 0.01f) return r.getScoreboardName() + " isn't hurt";
        if (donor.getHealth() - s().d("thread_lifeline.donorFloor") < 0.05) return "You're too hurt to give health";
        UUID other = RECIPIENTS.get(r.getUUID());
        if (other != null && !other.equals(donor.getUUID())) return "Someone is already giving " + r.getScoreboardName() + " health";
        stop(donor, now, "restart");
        CHANNELS.put(donor.getUUID(), new Channel(r, now, ThreadLinks.pos(donor)));
        RECIPIENTS.put(r.getUUID(), donor.getUUID());
        return null;
    }

    /** Key released (or any cancel). Starts the cooldown only if health actually moved. */
    public static void stop(ServerPlayerEntity donor, long now, String why) {
        Channel c = CHANNELS.remove(donor.getUUID());
        if (c == null) return;
        RECIPIENTS.remove(c.recipient);
        lastEnd = why;
        if (c.moved > 0f) COOLDOWN.put(donor.getUUID(), now + Math.round(s().d("thread_lifeline.cooldownSeconds") * 20));
    }

    public static int cooldown(ServerPlayerEntity p, long now) {
        Long until = COOLDOWN.get(p.getUUID());
        return until == null ? 0 : (int) Math.max(0, until - now);
    }

    /** Every tick for the donors of a world. */
    public static void tick(Iterable<ServerPlayerEntity> players, long now) {
        if (CHANNELS.isEmpty()) return;
        for (ServerPlayerEntity d : players) {
            Channel c = CHANNELS.get(d.getUUID());
            if (c == null) continue;
            String why = step(d, c, now);
            if (why != null) stop(d, now, why);
        }
    }

    /** One tick of a channel: null to continue, or the reason it ends. */
    static String step(ServerPlayerEntity d, Channel c, long now) {
        ServerPlayerEntity r = player(d, c.recipientId);
        if (r == null || !r.getUUID().equals(c.recipient)) return "recipient gone";
        if (!ThreadLinks.direct(d, r)) return "connection broken";
        double range = s().d("thread_lifeline.range");
        if (d.distanceToSqr(r) > (range + 0.5) * (range + 0.5)) return "out of range";
        if (!ThreadLinks.aimingAt(d, r, range + 0.5, 0.5)) return "looked away";
        Vector3d pos = ThreadLinks.pos(d);
        if (pos.distanceToSqr(c.lastPos) > 1.5 * 1.5) return "teleported";
        c.lastPos = pos;
        int aim = (int) Math.round(s().d("thread_lifeline.aimSeconds") * 20);
        int interval = Math.max(1, (int) Math.round(20 / Math.max(0.05, s().d("thread_lifeline.pointsPerSecond"))));
        if (now - c.start < aim) return null;
        if (c.lastTransfer >= 0 && now - c.lastTransfer < interval) return null;
        float gained = transfer(d, r, c);
        if (gained <= 0f) return c.moved > 0 ? "done" : "nothing to give";
        c.lastTransfer = now;
        ThreadNet.fxTeam(d, ThreadNet.FX_LIFELINE, d.getId(), r.getId(), gained);
        if (!c.reacted) {
            c.reacted = true;
            ThreadReactions.request(d.getLevel(), ThreadReactions.LIFELINE);
        }
        if (c.moved >= s().d("thread_lifeline.maxPerActivation") - 0.001) return "cap reached";
        return null;
    }

    /**
     * One transfer: heal the recipient by what the donor can pay, then debit the donor exactly what the recipient
     * really gained. Returns that amount.
     */
    static float transfer(ServerPlayerEntity d, ServerPlayerEntity r, Channel c) {
        float floor = (float) s().d("thread_lifeline.donorFloor");
        float allowed = Math.min((float) s().d("thread_lifeline.pointsPerTransfer"),
                Math.min((float) s().d("thread_lifeline.maxPerActivation") - c.moved, d.getHealth() - floor));
        float missing = r.getMaxHealth() - r.getHealth();
        allowed = Math.min(allowed, missing);
        if (allowed < 0.01f) return 0f;
        float before = r.getHealth();
        ThreadLinks.internal++;
        try {
            r.heal(allowed); // fires LivingHealEvent: other mechanics may cancel or change it
        } finally {
            ThreadLinks.internal--;
        }
        float gained = r.getHealth() - before;
        if (gained > allowed) { // a healing multiplier: never more than the donor pays
            r.setHealth(before + allowed);
            gained = allowed;
        }
        if (gained <= 0f) return 0f;
        float paid = ThreadLinks.pay(d, gained);
        if (paid < gained - 1e-4f) { // the donor's health could not be taken (another mod): undo the difference
            r.setHealth(r.getHealth() - (gained - paid));
            gained = paid;
        }
        c.moved += gained;
        totalMoved += gained;
        return gained;
    }

    /** Any real damage to the donor cancels its channel. */
    public static void onDonorHurt(ServerPlayerEntity d, long now) {
        if (CHANNELS.containsKey(d.getUUID())) stop(d, now, "took damage");
    }

    public static void onAction(ServerPlayerEntity d, long now, String what) {
        if (CHANNELS.containsKey(d.getUUID())) stop(d, now, what);
    }

    /** HUD: the donor's channel (target id, aim progress 0..1 or -1, health moved so far). */
    @Nullable
    static float[] channelOf(ServerPlayerEntity d, long now) {
        Channel c = CHANNELS.get(d.getUUID());
        if (c == null) return null;
        int aim = (int) Math.round(s().d("thread_lifeline.aimSeconds") * 20);
        return new float[]{c.recipientId, Math.min(1f, (now - c.start) / (float) Math.max(1, aim)), c.moved};
    }

    static boolean receiving(ServerPlayerEntity r) {
        return RECIPIENTS.containsKey(r.getUUID());
    }

    public static boolean channeling(ServerPlayerEntity d) {
        return CHANNELS.containsKey(d.getUUID());
    }

    public static float moved(ServerPlayerEntity d) {
        Channel c = CHANNELS.get(d.getUUID());
        return c == null ? 0f : c.moved;
    }

    public static void forget(UUID id) {
        Channel c = CHANNELS.remove(id); // a recipient who leaves ends the donor's channel on its next tick
        if (c != null) RECIPIENTS.remove(c.recipient);
    }

    public static void endAll() {
        CHANNELS.clear();
        RECIPIENTS.clear();
    }

    public static void clear() {
        endAll();
        COOLDOWN.clear();
        TEST_PLAYERS.clear();
    }

}
