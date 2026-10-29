package net.ecmoons.feature.reveal;

import net.ecmoons.config.Settings;
import net.ecmoons.feature.ability.MoonAbilities;
import net.ecmoons.feature.war.WarArena;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.pathfinding.GroundPathNavigator;
import net.minecraft.pathfinding.PathNavigator;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The World Notices You: at a scan's completion, a few nearby hostile mobs that are already loaded, idle (no combat
 * target) and of an allowed type walk to the spot where a revealed player stood AT THAT MOMENT, look around there
 * briefly and then return to their own AI. It is a snapshot, never tracking: the position is not updated.
 * <p>
 * Only ordinary path navigation is used (PathNavigator.moveTo) - no attack target is set, no goals are added or
 * removed, follow range is unchanged, nothing is spawned, teleported or chunk-loaded. A mob that genuinely notices
 * a player through its own targeting drops the investigation immediately. Each mob gets at most one assignment;
 * selection is deterministic (nearest first, then UUID) and bounded per player and per scan. All state lives in
 * this class (nothing is written to the mob) and is removed at timeout, when the moon ends, or when the revealed
 * player dies, leaves the dimension, logs out or becomes ineligible.
 */
public final class WorldNotices {
    static final class Job {
        final MobEntity mob;
        final UUID player;
        final Vector3d spot;
        final long start;
        long arrived = -1, lastPath = -1_000_000L;
        int failedPaths;

        Job(MobEntity mob, UUID player, Vector3d spot, long start) {
            this.mob = mob;
            this.player = player;
            this.spot = spot;
            this.start = start;
        }
    }

    private static final Map<RegistryKey<World>, Map<Integer, Job>> JOBS = new LinkedHashMap<>();
    /** Investigations started / ended because the mob found a target on its own (diagnostics / tests). */
    public static int started, tookOver;

    private WorldNotices() {}

    public static boolean enabled() {
        return Settings.server().b("revealing_investigate.enabled");
    }

    /** Scan completion: assign investigators for the revealed players (already in deterministic order). */
    public static int onScan(ServerWorld world, List<ServerPlayerEntity> revealed, long now) {
        if (!enabled() || world.dimension().equals(WarArena.DIMENSION)) return 0;
        Settings s = Settings.server();
        double r = s.d("revealing_investigate.radius");
        int perPlayer = s.i("revealing_investigate.maxPerPlayer");
        int budget = s.i("revealing_investigate.maxPerScan");
        int players = s.i("revealing_investigate.maxPlayersPerScan");
        boolean respect = s.b("revealing_investigate.respectProtection");
        Set<ResourceLocation> allowed = allowList();
        Map<Integer, Job> jobs = JOBS.computeIfAbsent(world.dimension(), k -> new LinkedHashMap<>());
        int total = 0, handled = 0;
        for (ServerPlayerEntity p : revealed) {
            if (budget <= 0 || handled >= players) break;
            handled++;
            Vector3d spot = new Vector3d(p.getX(), p.getY(), p.getZ());
            BlockPos pos = p.blockPosition();
            if (respect && !allowedSpot(world, p, pos)) continue;
            List<MobEntity> found = world.getEntitiesOfClass(MobEntity.class, new AxisAlignedBB(spot, spot).inflate(r),
                    m -> eligible(m, allowed) && !jobs.containsKey(m.getId()) && m.distanceToSqr(spot) <= r * r);
            found.sort(Comparator.<MobEntity>comparingDouble(m -> m.distanceToSqr(spot)).thenComparing(MobEntity::getUUID));
            int n = 0;
            for (MobEntity m : found) {
                if (n >= perPlayer || budget <= 0) break;
                Job j = new Job(m, p.getUUID(), spot, now);
                jobs.put(m.getId(), j);
                path(j, now);
                n++;
                budget--;
                total++;
                started++;
            }
        }
        return total;
    }

    /** Ordinary hostile ground mobs of an allowed type, idle, not tamed, not ridden, with AI. */
    static boolean eligible(MobEntity m, Set<ResourceLocation> allowed) {
        if (!m.isAlive() || m.isNoAi() || m.getTarget() != null || m.isPassenger() || m.isVehicle() || m.isLeashed()) return false;
        if (!(m instanceof IMob)) return false; // never neutral creatures, villagers, golems or pets
        if (m instanceof TameableEntity && ((TameableEntity) m).isTame()) return false;
        if (!(m.getNavigation() instanceof GroundPathNavigator)) return false; // ground walkers only
        if (m.getTags().contains(net.ecmoons.feature.RevealingFeatures.EXEMPT_TAG)) return false;
        ResourceLocation id = EntityType.getKey(m.getType());
        return allowed.contains(id);
    }

    /** Respect protection: never send mobs into spawn protection, or a spot where the player could not build (claims). */
    static boolean allowedSpot(ServerWorld world, ServerPlayerEntity p, BlockPos pos) {
        if (world.getServer().isUnderSpawnProtection(world, pos, p)) return false;
        return MoonAbilities.canPlace(p, pos);
    }

    static Set<ResourceLocation> allowList() {
        Set<ResourceLocation> set = new HashSet<>();
        for (String id : Settings.server().list("revealing_investigate.mobs")) {
            ResourceLocation rl = ResourceLocation.tryParse(id.trim());
            if (rl != null) set.add(rl);
        }
        return set;
    }

    /** Every 5 ticks: walk, search, or finish each investigation in this world. */
    public static void tick(ServerWorld world, long now) {
        Map<Integer, Job> jobs = JOBS.get(world.dimension());
        if (jobs == null || jobs.isEmpty()) return;
        Settings s = Settings.server();
        int timeout = (int) Math.round(s.d("revealing_investigate.seconds") * 20);
        int search = (int) Math.round(s.d("revealing_investigate.searchSeconds") * 20);
        for (Iterator<Job> it = jobs.values().iterator(); it.hasNext(); ) {
            Job j = it.next();
            MobEntity m = j.mob;
            if (!m.isAlive() || m.removed || m.level != world) { it.remove(); continue; }
            if (m.getTarget() != null) { tookOver++; it.remove(); continue; } // normal targeting took over: leave its AI alone
            if (now - j.start >= timeout) { finish(j); it.remove(); continue; }
            double d2 = m.distanceToSqr(j.spot);
            if (j.arrived < 0 && d2 < 2.5 * 2.5) j.arrived = now;
            if (j.arrived >= 0) {
                if (now - j.arrived >= search) { finish(j); it.remove(); continue; }
                // look around the old spot (deterministic sweep), staying near it
                double a = ((now - j.arrived) / 10) * 2.1 + (m.getId() % 7);
                m.getLookControl().setLookAt(j.spot.x + Math.cos(a) * 4, j.spot.y + 1, j.spot.z + Math.sin(a) * 4, 10f, 40f);
                continue;
            }
            path(j, now);
            if (j.failedPaths >= 3) { it.remove(); }
        }
    }

    private static void path(Job j, long now) {
        PathNavigator nav = j.mob.getNavigation();
        BlockPos target = new BlockPos(j.spot);
        boolean ours = !nav.isDone() && target.equals(nav.getTargetPos());
        if (ours || now - j.lastPath < 20) return;
        j.lastPath = now;
        double speed = Settings.server().d("revealing_investigate.speed");
        if (!nav.moveTo(j.spot.x, j.spot.y, j.spot.z, speed)) j.failedPaths++;
    }

    /** Stop only our own path; any target or other path the mob picked meanwhile is kept. */
    private static void finish(Job j) {
        PathNavigator nav = j.mob.getNavigation();
        if (j.mob.isAlive() && j.mob.getTarget() == null && !nav.isDone() && new BlockPos(j.spot).equals(nav.getTargetPos())) nav.stop();
    }

    public static int active(World world) {
        Map<Integer, Job> jobs = JOBS.get(world.dimension());
        return jobs == null ? 0 : jobs.size();
    }

    public static boolean investigating(MobEntity m) {
        Map<Integer, Job> jobs = JOBS.get(m.level.dimension());
        return jobs != null && jobs.containsKey(m.getId());
    }

    /** Spot assigned to this mob (tests), or null. */
    public static Vector3d spotOf(MobEntity m) {
        Map<Integer, Job> jobs = JOBS.get(m.level.dimension());
        Job j = jobs == null ? null : jobs.get(m.getId());
        return j == null ? null : j.spot;
    }

    /** The revealed player died, left, logged out or became ineligible: their investigations end now. */
    public static void forgetPlayer(UUID player) {
        for (Map<Integer, Job> jobs : JOBS.values()) {
            for (Iterator<Job> it = jobs.values().iterator(); it.hasNext(); ) {
                Job j = it.next();
                if (j.player.equals(player)) {
                    finish(j);
                    it.remove();
                }
            }
        }
    }

    /** Moon ended / admin stop in this world. */
    public static void endAll(ServerWorld world) {
        Map<Integer, Job> jobs = JOBS.remove(world.dimension());
        if (jobs != null) for (Job j : new ArrayList<>(jobs.values())) finish(j);
    }

    public static void clear() {
        JOBS.clear();
    }
}
