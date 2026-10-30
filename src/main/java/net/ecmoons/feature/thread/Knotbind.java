package net.ecmoons.feature.thread;

import net.ecmoons.config.Settings;
import net.ecmoons.feature.ability.wisp.ModEntities;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Knotbind: a yarn projectile (Moon Ability key, ordinary press) marks a first enemy for 3 s; a second hit on a
 * DIFFERENT enemy within 6 blocks of the first, with a clear line between them, knots the two together for 5 s.
 * <p>
 * Targets: players who are enemies under /moonteams (never self, teammates, spectators or War Moon fighters) and
 * allowlisted ordinary hostile mobs (never bosses, pets, tamed or neutral mobs). The caster must be allowed to hurt
 * each target - server PvP setting, vanilla team friendly fire and any protection mod that cancels an attack
 * (AttackEntityEvent / LivingAttackEvent probes: Flan claims, World Protector regions...) - at both hits, at pair
 * creation and again while the knot holds. One knot per caster, one knot per target, no chains.
 * <p>
 * The knot never deals damage and never roots anyone: inside 6 blocks both move, fight and use items freely. Beyond
 * it, a small, bounded horizontal velocity nudges them back together (no teleports, no vertical lift, collisions,
 * gravity and knockback stay normal). It breaks at once if a block comes between them, either is teleported, the
 * stretch can't be corrected, either dies/leaves/changes dimension/becomes protected or not an enemy, or the moon ends.
 * Either victim can stand still and hold the Moon Ability key for 1.5 s to unravel it for both. Any release gives
 * both 8 s of server-side immunity to a new knot (from any caster).
 */
public final class Knotbind {
    static final class Mark {
        final LivingEntity first;
        final long until;

        Mark(LivingEntity first, long until) {
            this.first = first;
            this.until = until;
        }
    }

    public static final class Knot {
        public final int id;
        final UUID caster;
        public final LivingEntity a, b;
        final long start, end;
        Vector3d lastA, lastB;
        long holdA = -1, holdB = -1;

        Knot(int id, UUID caster, LivingEntity a, LivingEntity b, long start, long end) {
            this.id = id;
            this.caster = caster;
            this.a = a;
            this.b = b;
            this.start = start;
            this.end = end;
            lastA = ThreadLinks.pos(a);
            lastB = ThreadLinks.pos(b);
        }

        boolean has(Entity e) {
            return e == a || e == b;
        }

        public boolean has2(Entity x, Entity y) {
            return (a == x && b == y) || (a == y && b == x);
        }

        public float progress(Entity e, long now) {
            long h = e == a ? holdA : e == b ? holdB : -1;
            if (h < 0) return 0f;
            return Math.min(1f, (now - h) / (float) unravelTicks());
        }
    }

    private static final Map<UUID, Mark> MARKS = new HashMap<>();
    private static final List<Knot> KNOTS = new ArrayList<>();
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>(), LAST_SHOT = new HashMap<>(), IMMUNE = new HashMap<>();
    private static final List<YarnEntity> YARN = new ArrayList<>();
    private static int nextId = 1;
    /** Tests: last failure message per caster, knots made, releases with their reasons. */
    public static final Map<UUID, String> LAST_MSG = new HashMap<>();
    public static int made;
    public static String lastRelease = "";

    private Knotbind() {}

    static Settings s() {
        return Settings.server();
    }

    static long now() {
        return net.ecmoons.feature.ability.MoonAbilities.now();
    }

    static int unravelTicks() {
        return (int) Math.round(s().d("thread_knotbind.unravelSeconds") * 20);
    }

    // ---- firing ------------------------------------------------------------------------------------------------

    /** Moon Ability key, ordinary press. Returns null when a yarn was thrown, else the reason. */
    @Nullable
    public static String fire(ServerPlayerEntity caster) {
        long now = now();
        if (!s().b("thread_knotbind.enabled")) return "Knotbind is disabled";
        if (!ThreadLinks.moonUp(caster) || !ThreadLinks.eligible(caster)) return "You can't use that now";
        if (victim(caster)) return null; // the key unravels instead (handled by the caller)
        if (ownsKnot(caster)) return "Your knot is still holding";
        Long last = LAST_SHOT.get(caster.getUUID());
        if (last != null && now - last < s().i("thread_knotbind.shotIntervalTicks")) return "";
        Mark m = MARKS.get(caster.getUUID());
        boolean marking = m != null && m.until > now;
        if (!marking && cooldown(caster, now) > 0) return "Knotbind recovers in " + (cooldown(caster, now) + 19) / 20 + "s";
        LAST_SHOT.put(caster.getUUID(), now);
        YarnEntity y = ModEntities.YARN.get().create(caster.level);
        if (y == null) return "";
        Vector3d eye = caster.getEyePosition(1f), look = caster.getViewVector(1f);
        double speed = s().d("thread_knotbind.projectileSpeed");
        y.moveTo(eye.x + look.x * 0.6, eye.y - 0.1 + look.y * 0.6, eye.z + look.z * 0.6, caster.yRot, caster.xRot);
        y.setDeltaMovement(look.scale(speed));
        y.owner = caster.getUUID();
        y.life = (int) Math.ceil(s().d("thread_knotbind.range") / speed);
        caster.level.addFreshEntity(y);
        YARN.add(y);
        return null;
    }

    /** The server decides every hit. */
    static void onYarnHit(YarnEntity y, LivingEntity target) {
        if (y.owner == null || !(y.level instanceof ServerWorld)) return;
        ServerPlayerEntity caster = ((ServerWorld) y.level).getServer().getPlayerList().getPlayer(y.owner);
        if (caster == null) caster = Lifeline.TEST_PLAYERS.values().stream().filter(p -> p.getUUID().equals(y.owner)).findFirst().orElse(null);
        if (caster != null) hit(caster, target, now());
    }

    /** A valid hit on target by caster (also used directly by the self test). */
    public static void hit(ServerPlayerEntity caster, LivingEntity target, long now) {
        if (!ThreadLinks.eligible(caster) || !ThreadLinks.moonUp(caster) || ownsKnot(caster)) return;
        String why = invalid(caster, target, now);
        Mark m = MARKS.get(caster.getUUID());
        if (m != null && m.until <= now) {
            MARKS.remove(caster.getUUID());
            m = null;
        }
        if (m == null) {
            if (why != null) {
                tell(caster, why);
                return;
            }
            if (cooldown(caster, now) > 0) return;
            MARKS.put(caster.getUUID(), new Mark(target, now + Math.round(s().d("thread_knotbind.markSeconds") * 20)));
            COOLDOWN.put(caster.getUUID(), now + Math.round(s().d("thread_knotbind.cooldownSeconds") * 20));
            if (target instanceof ServerPlayerEntity) ThreadNet.notice((ServerPlayerEntity) target, "A yarn thread tagged you");
            ThreadNet.dirty(caster);
            return;
        }
        if (target == m.first) return; // re-hitting the first target does not refresh the timer
        String pairWhy = why != null ? why : pairInvalid(caster, m.first, target, now);
        if (pairWhy != null) {
            tell(caster, pairWhy);
            return;
        }
        MARKS.remove(caster.getUUID());
        Knot k = new Knot(nextId++, caster.getUUID(), m.first, target, now, now + Math.round(s().d("thread_knotbind.durationSeconds") * 20));
        KNOTS.add(k);
        made++;
        if (m.first instanceof ServerPlayerEntity) ThreadNet.notice((ServerPlayerEntity) m.first, "Knotted! Stand still and hold the Moon Ability key to unravel");
        if (target instanceof ServerPlayerEntity) ThreadNet.notice((ServerPlayerEntity) target, "Knotted! Stand still and hold the Moon Ability key to unravel");
        ThreadNet.knotsChanged((ServerWorld) caster.level);
        ThreadReactions.request(caster.getLevel(), ThreadReactions.KNOT);
    }

    private static void tell(ServerPlayerEntity caster, String why) {
        LAST_MSG.put(caster.getUUID(), why);
        ThreadNet.notice(caster, why);
    }

    /** Is this a legal Knotbind target for this caster right now? null if yes, else the reason. */
    @Nullable
    public static String invalid(ServerPlayerEntity caster, LivingEntity t, long now) {
        if (t == caster) return "You can't knot yourself";
        if (!t.isAlive() || t.level != caster.level) return "That target isn't valid";
        if (inKnot(t)) return "Already knotted";
        Long imm = IMMUNE.get(t.getUUID());
        if (imm != null && imm > now) return "Still loose from the last knot";
        if (t instanceof ServerPlayerEntity) {
            ServerPlayerEntity p = (ServerPlayerEntity) t;
            if (!ThreadLinks.eligible(p)) return "That player can't be knotted";
            if (!ThreadLinks.enemies(caster, p)) return "Only enemies can be knotted";
        } else if (!mobAllowed(t)) return "That creature can't be knotted";
        if (!mayRestrain(caster, t)) return "Protected - you can't restrain them here";
        return null;
    }

    static boolean mobAllowed(LivingEntity t) {
        if (!(t instanceof MobEntity) || !(t instanceof IMob)) return false;
        if (t instanceof TameableEntity && ((TameableEntity) t).isTame()) return false;
        ResourceLocation id = EntityType.getKey(t.getType());
        for (String s : s().list("thread_knotbind.mobs")) if (id.toString().equals(s.trim())) return true;
        return false;
    }

    /**
     * Hostile permission: server PvP, vanilla friendly fire, and protection mods (claims, regions, safe zones) asked
     * through the same cancellable events a real attack would fire. No damage is dealt.
     */
    public static boolean mayRestrain(ServerPlayerEntity caster, LivingEntity t) {
        if (t instanceof ServerPlayerEntity) {
            if (!caster.server.isPvpAllowed()) return false;
            if (!caster.canHarmPlayer((ServerPlayerEntity) t)) return false;
        }
        ThreadMoon.probing = true;
        try {
            if (MinecraftForge.EVENT_BUS.post(new AttackEntityEvent(caster, t))) return false;
            return !MinecraftForge.EVENT_BUS.post(new LivingAttackEvent(t, DamageSource.playerAttack(caster), 1f));
        } finally {
            ThreadMoon.probing = false;
        }
    }

    /** Diagnostics for the hostile-permission probes. */
    public static String explainRestrain(ServerPlayerEntity caster, LivingEntity t) {
        String r = "pvp=" + caster.server.isPvpAllowed();
        if (t instanceof ServerPlayerEntity) r += " canHarm=" + caster.canHarmPlayer((ServerPlayerEntity) t);
        ThreadMoon.probing = true;
        try {
            r += " attackEvt=" + !MinecraftForge.EVENT_BUS.post(new AttackEntityEvent(caster, t));
            r += " livingAttack=" + !MinecraftForge.EVENT_BUS.post(new LivingAttackEvent(t, DamageSource.playerAttack(caster), 1f));
        } finally {
            ThreadMoon.probing = false;
        }
        return r;
    }

    @Nullable
    static String pairInvalid(ServerPlayerEntity caster, LivingEntity a, LivingEntity b, long now) {
        if (invalid(caster, a, now) != null) return "The first target is no longer valid";
        if (a.level != b.level) return "They're not in the same place";
        double max = s().d("thread_knotbind.maxPairDistance");
        if (a.distanceToSqr(b) > max * max) return "Too far apart (more than " + (int) max + " blocks)";
        if (!ThreadLinks.lineOfSight(a, b)) return "Something is between them";
        return null;
    }

    // ---- the knot ----------------------------------------------------------------------------------------------

    /** Every tick per world. */
    public static void tick(ServerWorld w, long now) {
        if (!MARKS.isEmpty()) MARKS.values().removeIf(m -> m.until <= now || !m.first.isAlive());
        YARN.removeIf(y -> y.removed);
        for (Iterator<Knot> it = KNOTS.iterator(); it.hasNext(); ) {
            Knot k = it.next();
            if (k.a.level != w && k.b.level != w) continue;
            String why = step(w, k, now);
            if (why != null) {
                it.remove();
                released(w, k, now, why);
            }
        }
    }

    /** One tick of a knot: null to continue or the reason it breaks. */
    static String step(ServerWorld w, Knot k, long now) {
        if (now >= k.end) return "expired";
        if (!k.a.isAlive() || !k.b.isAlive() || k.a.removed || k.b.removed) return "gone";
        if (k.a.level != w || k.b.level != w) return "dimension";
        if (ThreadLinks.pos(k.a).distanceToSqr(k.lastA) > 25 || ThreadLinks.pos(k.b).distanceToSqr(k.lastB) > 25) return "teleported";
        for (LivingEntity e : new LivingEntity[]{k.a, k.b}) {
            if (e instanceof ServerPlayerEntity) {
                ServerPlayerEntity p = (ServerPlayerEntity) e;
                if (!ThreadLinks.eligible(p) || p.hasDisconnected()) return "ineligible";
            }
        }
        if ((now - k.start) % 10 == 0) { // revalidate hostility and protection
            ServerPlayerEntity caster = w.getServer().getPlayerList().getPlayer(k.caster);
            if (caster == null) caster = Lifeline.TEST_PLAYERS.values().stream().filter(p -> p.getUUID().equals(k.caster)).findFirst().orElse(null);
            if (caster == null || !ThreadLinks.moonUp(caster)) return "caster gone";
            for (LivingEntity e : new LivingEntity[]{k.a, k.b}) {
                if (e instanceof ServerPlayerEntity && !ThreadLinks.enemies(caster, (ServerPlayerEntity) e)) return "no longer enemies";
                if (!mayRestrain(caster, e)) return "protected";
            }
        }
        if (!ThreadLinks.lineOfSight(k.a, k.b)) return "obstructed";
        double len = s().d("thread_knotbind.tetherLength");
        Vector3d pa = ThreadLinks.pos(k.a), pb = ThreadLinks.pos(k.b);
        double d = pa.distanceTo(pb);
        if (d > len + s().d("thread_knotbind.breakStretch")) return "stretched";
        if (d > len) pull(k, pa, pb, d - len);
        k.lastA = ThreadLinks.pos(k.a);
        k.lastB = ThreadLinks.pos(k.b);
        for (LivingEntity e : new LivingEntity[]{k.a, k.b}) {
            if (k.progress(e, now) >= 1f) return "unravelled";
        }
        return null;
    }

    /** A small, bounded horizontal nudge of both towards each other; never sets a position or erases velocity. */
    static void pull(Knot k, Vector3d pa, Vector3d pb, double excess) {
        double dx = pb.x - pa.x, dz = pb.z - pa.z, h = Math.sqrt(dx * dx + dz * dz);
        if (h < 1e-4) return;
        dx /= h;
        dz /= h;
        double f = Math.min(s().d("thread_knotbind.maxPull"), s().d("thread_knotbind.pullStrength") * excess);
        double cap = s().d("thread_knotbind.maxPullSpeed");
        nudge(k.a, dx, dz, f, cap);
        nudge(k.b, -dx, -dz, f, cap);
    }

    private static void nudge(LivingEntity e, double dx, double dz, double f, double cap) {
        Vector3d v = e.getDeltaMovement();
        double toward = v.x * dx + v.z * dz;
        double add = Math.max(0, Math.min(f, cap - toward));
        if (add <= 0) return;
        e.setDeltaMovement(v.x + dx * add, v.y, v.z + dz * add);
        e.hurtMarked = true; // players get the velocity like knockback
    }

    static void released(ServerWorld w, Knot k, long now, String why) {
        long imm = now + Math.round(s().d("thread_knotbind.immunitySeconds") * 20);
        IMMUNE.put(k.a.getUUID(), imm);
        IMMUNE.put(k.b.getUUID(), imm);
        lastRelease = why;
        ThreadNet.knotReleased(w, k);
        for (LivingEntity e : new LivingEntity[]{k.a, k.b}) {
            if (e instanceof ServerPlayerEntity) ThreadNet.notice((ServerPlayerEntity) e, "unravelled".equals(why) ? "Unravelled!" : "The knot came loose");
        }
        ThreadNet.knotsChanged(w);
    }

    // ---- Unravel -----------------------------------------------------------------------------------------------

    /** Victim holds the key (start), lets go (stop) or moved/attacked/used an item (reset). */
    public static void unravel(LivingEntity victim, boolean holding, long now) {
        Knot k = knotOf(victim);
        if (k == null) return;
        long v = holding ? (k.progress(victim, now) > 0 ? (victim == k.a ? k.holdA : k.holdB) : now) : -1;
        if (victim == k.a) k.holdA = v;
        else k.holdB = v;
        if (victim.level instanceof ServerWorld) ThreadNet.knotsChanged((ServerWorld) victim.level);
    }

    /** Voluntary movement, attacking or item use: that victim's progress starts over (still holding = from now). */
    public static void resetUnravel(LivingEntity victim, long now) {
        Knot k = knotOf(victim);
        if (k == null) return;
        if (victim == k.a && k.holdA >= 0) k.holdA = now;
        if (victim == k.b && k.holdB >= 0) k.holdB = now;
    }

    // ---- queries -----------------------------------------------------------------------------------------------

    @Nullable
    public static Knot knotOf(Entity e) {
        for (Knot k : KNOTS) if (k.has(e)) return k;
        return null;
    }

    public static boolean inKnot(Entity e) {
        return knotOf(e) != null;
    }

    public static boolean victim(ServerPlayerEntity p) {
        return inKnot(p);
    }

    public static boolean ownsKnot(ServerPlayerEntity p) {
        for (Knot k : KNOTS) if (k.caster.equals(p.getUUID())) return true;
        return false;
    }

    public static List<Knot> knots() {
        return KNOTS;
    }

    @Nullable
    static Mark markOf(ServerPlayerEntity caster) {
        return MARKS.get(caster.getUUID());
    }

    /** Is this entity currently a first mark (for its own "tagged" indicator)? Remaining ticks, 0 if not. */
    static int taggedFor(Entity e, long now) {
        for (Mark m : MARKS.values()) if (m.first == e) return (int) Math.max(0, m.until - now);
        return 0;
    }

    public static int cooldown(ServerPlayerEntity p, long now) {
        Long c = COOLDOWN.get(p.getUUID());
        return c == null ? 0 : (int) Math.max(0, c - now);
    }

    public static boolean immune(Entity e, long now) {
        Long c = IMMUNE.get(e.getUUID());
        return c != null && c > now;
    }

    /** A player died, left or changed dimension: their marks and knots end now. */
    public static void forget(ServerPlayerEntity p) {
        MARKS.remove(p.getUUID());
        MARKS.values().removeIf(m -> m.first == p);
        for (Iterator<Knot> it = KNOTS.iterator(); it.hasNext(); ) {
            Knot k = it.next();
            if (k.has(p)) {
                it.remove();
                released(p.getLevel(), k, now(), "player left");
            }
        }
    }

    /** The moon ended in this world (also /ecmoons end): knots, marks and flying yarn all go. */
    public static void endAll(ServerWorld w) {
        long now = now();
        for (Iterator<Knot> it = KNOTS.iterator(); it.hasNext(); ) {
            Knot k = it.next();
            if (k.a.level == w || k.b.level == w) {
                it.remove();
                released(w, k, now, "moon ended");
            }
        }
        MARKS.values().removeIf(m -> m.first.level == w);
        for (YarnEntity y : YARN) if (y.level == w) y.remove();
        YARN.removeIf(y -> y.removed);
    }

    public static void clear() {
        MARKS.clear();
        KNOTS.clear();
        COOLDOWN.clear();
        LAST_SHOT.clear();
        IMMUNE.clear();
        YARN.clear();
        LAST_MSG.clear();
    }

    /** Tests: forget cooldowns and immunity. */
    public static void resetTimers() {
        COOLDOWN.clear();
        LAST_SHOT.clear();
        IMMUNE.clear();
    }
}
