package net.ecmoons.feature;

import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Settings;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thread Moon. Five times a second (connectionIntervalTicks), per world with the moon up, players of the same /moonteams team within bondRange and
 * with a clear line of sight between their eyes are bonded. Each bonded teammate gives reductionPerTeammate damage
 * reduction, capped at maxReduction. When teammates separate, the reduction fades over fadeSeconds.
 * <p>
 * The reduction is applied once per damage event (LivingHurtEvent) and is a plain reduction: no damage is moved to
 * teammates. Damage types in excludedDamageTypes are never reduced. Spectators, dead players, players without a
 * team, players in other dimensions and War Moon participants never bond. Pets and other mobs are not players and
 * never take part.
 */
public final class ThreadFeatures {
    private static final Map<UUID, Float> CURRENT = new HashMap<>();

    private ThreadFeatures() {}

    public static float reduction(ServerPlayerEntity p) {
        return CURRENT.getOrDefault(p.getUUID(), 0f);
    }

    private static boolean canBond(ServerPlayerEntity p) {
        return net.ecmoons.feature.thread.ThreadLinks.eligible(p);
    }

    static boolean lineOfSight(ServerWorld w, ServerPlayerEntity a, ServerPlayerEntity b) {
        Vector3d from = a.getEyePosition(1f), to = b.getEyePosition(1f);
        BlockRayTraceResult hit = w.clip(new RayTraceContext(from, to, RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, a));
        return hit.getType() == RayTraceResult.Type.MISS;
    }

    public static boolean lineOfSightForTest(ServerWorld w, ServerPlayerEntity a, ServerPlayerEntity b) {
        return lineOfSight(w, a, b);
    }

    /** Team id -> bonded link pairs (entity ids) from the last evaluation, per world (sent only to that team). */
    private static final Map<String, int[]> LINKS = new HashMap<>();
    private static final Map<UUID, Integer> COUNTS = new HashMap<>();

    /**
     * Every connectionIntervalTicks (4 = five times a second) per world: bonds, the passive reduction (rising at once,
     * fading over fadeSeconds) and the team link lists. Packets are built by ThreadNet.
     */
    public static void tick(ServerWorld world, MoonType active, int interval) {
        if (active != MoonType.THREAD && CURRENT.isEmpty() && LINKS.isEmpty()) return;
        MoonTeams teams = MoonTeams.get(world.getServer());
        Settings s = Settings.server();
        boolean on = active == MoonType.THREAD;
        double range = s.d("thread.bondRange");
        float per = (float) s.d("thread.reductionPerTeammate");
        float max = (float) s.d("thread.maxReduction");
        int fade = s.i("thread.fadeSeconds");
        float step = fade <= 0 ? 1f : max * interval / (fade * 20f); // the same fade time at any interval
        boolean los = s.b("thread.requireLineOfSight");

        // team -> eligible members in this world
        Map<String, List<ServerPlayerEntity>> byTeam = new HashMap<>();
        for (ServerPlayerEntity p : world.players()) {
            String team = teams.teamOf(p.getUUID());
            if (on && team != null && canBond(p)) byTeam.computeIfAbsent(team, k -> new ArrayList<>()).add(p);
        }
        Map<UUID, Integer> counts = new HashMap<>();
        for (Map.Entry<String, List<ServerPlayerEntity>> e : byTeam.entrySet()) {
            List<Integer> links = new ArrayList<>();
            bond(world, e.getValue(), range, los, counts, links);
            LINKS.put(world.dimension().location() + "|" + e.getKey(), links.stream().mapToInt(Integer::intValue).toArray());
        }
        String prefix = world.dimension().location() + "|";
        LINKS.keySet().removeIf(k -> k.startsWith(prefix) && !byTeam.containsKey(k.substring(prefix.length())));
        for (ServerPlayerEntity p : world.players()) {
            int n = counts.getOrDefault(p.getUUID(), 0);
            if (n > 0) COUNTS.put(p.getUUID(), n);
            else COUNTS.remove(p.getUUID());
            float target = Math.min(max, n * per);
            float cur = CURRENT.getOrDefault(p.getUUID(), 0f);
            float next = target >= cur ? target : Math.max(target, cur - step);
            if (!on || !canBond(p)) next = 0f;
            if (next > 0f) CURRENT.put(p.getUUID(), next);
            else CURRENT.remove(p.getUUID());
        }
    }

    /** The bonded links of this team (entity id pairs), for its own members only. */
    public static int[] links(ServerWorld world, String team) {
        int[] l = team == null ? null : LINKS.get(world.dimension().location() + "|" + team);
        return l == null ? new int[0] : l;
    }

    /** Teammates this player is directly bonded with at the last evaluation. */
    public static int linked(ServerPlayerEntity p) {
        return COUNTS.getOrDefault(p.getUUID(), 0);
    }

    /**
     * Bond the members of one team: every pair within range (and in line of sight when required) is a link and
     * counts one teammate for each of the two players.
     */
    public static void bond(ServerWorld world, List<ServerPlayerEntity> members, double range, boolean los,
                            Map<UUID, Integer> counts, List<Integer> links) {
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                ServerPlayerEntity a = members.get(i), b = members.get(j);
                double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
                if (dx * dx + dy * dy + dz * dz > range * range) continue;
                if (los && !lineOfSight(world, a, b)) continue;
                counts.merge(a.getUUID(), 1, Integer::sum);
                counts.merge(b.getUUID(), 1, Integer::sum);
                links.add(a.getId());
                links.add(b.getId());
            }
        }
    }

    /** Reduction for this many bonded teammates. */
    public static float target(int teammates) {
        Settings s = Settings.server();
        return Math.min((float) s.d("thread.maxReduction"), teammates * (float) s.d("thread.reductionPerTeammate"));
    }

    /** Damage after the bond reduction (excluded damage types are never reduced). */
    public static float reduce(float amount, float reduction, DamageSource source) {
        return reduction <= 0f || excluded(source) ? amount : amount * (1f - reduction);
    }

    public static boolean excluded(DamageSource source) {
        return Settings.server().list("thread.excludedDamageTypes").contains(source.getMsgId());
    }

    /** Applied once per damage event; the reduction is never redistributed. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(LivingHurtEvent event) {
        if (!(event.getEntityLiving() instanceof ServerPlayerEntity) || net.ecmoons.feature.thread.ThreadLinks.internal()) return;
        ServerPlayerEntity p = (ServerPlayerEntity) event.getEntityLiving();
        float r = reduction(p);
        if (r <= 0f || !Moons.is(p.level, MoonType.THREAD) || !canBond(p)) return;
        event.setAmount(reduce(event.getAmount(), r, event.getSource()));
    }

    public static void forget(UUID id) {
        CURRENT.remove(id);
        COUNTS.remove(id);
    }

    public static void clear() {
        CURRENT.clear();
        COUNTS.clear();
        LINKS.clear();
    }

    public static String describe(ServerPlayerEntity p) {
        MoonTeams teams = MoonTeams.get(p.server);
        String team = teams.teamOf(p.getUUID());
        return p.getScoreboardName() + ": team " + (team == null ? "none" : team + " (" + teams.displayName(team) + ")")
                + ", bond reduction " + Math.round(reduction(p) * 100) + "%" + (Moons.is(p.level, MoonType.THREAD) ? "" : " (Thread Moon not active here)");
    }
}
