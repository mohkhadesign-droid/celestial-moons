package net.ecmoons.feature.thread;

import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.MoonTeams;
import net.ecmoons.feature.war.WarArena;
import net.ecmoons.feature.war.WarFeatures;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;

import javax.annotation.Nullable;

/**
 * The Thread Moon's shared rules. Teams only ever come from /moonteams (MoonTeams); nothing is inferred from names,
 * colours, claims or scoreboards. A DIRECT connection is two eligible teammates in the same dimension within
 * bondRange of each other with a clear line between their eyes - checked fresh here for every transaction, and never
 * transitive.
 */
public final class ThreadLinks {
    private ThreadLinks() {}

    /** Alive, not a spectator, not in the War Moon arena or tournament (the existing exclusions). */
    public static boolean eligible(@Nullable ServerPlayerEntity p) {
        return p != null && p.isAlive() && !p.isSpectator() && !WarFeatures.isInvolved(p)
                && !p.level.dimension().equals(WarArena.DIMENSION);
    }

    public static boolean moonUp(ServerPlayerEntity p) {
        return Moons.is(p.level, MoonType.THREAD);
    }

    @Nullable
    public static String team(ServerPlayerEntity p) {
        return MoonTeams.get(p.server).teamOf(p.getUUID());
    }

    public static boolean teammates(ServerPlayerEntity a, ServerPlayerEntity b) {
        if (a == b) return false;
        String ta = team(a);
        return ta != null && ta.equals(team(b));
    }

    /** Enemies for Knotbind: both on a /moonteams team, different teams (requireTeams), or simply not teammates. */
    public static boolean enemies(ServerPlayerEntity a, ServerPlayerEntity b) {
        if (a == b) return false;
        String ta = team(a), tb = team(b);
        if (Settings.server().b("thread_knotbind.requireTeams")) return ta != null && tb != null && !ta.equals(tb);
        return ta == null || !ta.equals(tb);
    }

    public static boolean lineOfSight(Entity a, Entity b) {
        Vector3d from = a.getEyePosition(1f), to = b.getEyePosition(1f);
        return a.level.clip(new RayTraceContext(from, to, RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, a))
                .getType() == RayTraceResult.Type.MISS;
    }

    /** A current, direct connection (the same rule as the passive bond, evaluated now). */
    public static boolean direct(ServerPlayerEntity a, ServerPlayerEntity b) {
        return direct(a, b, false);
    }

    /** As above; dyingB lets b be at zero health (Last Stitch is decided while the hit is killing b). */
    public static boolean direct(ServerPlayerEntity a, ServerPlayerEntity b, boolean dyingB) {
        boolean bOk = dyingB ? b != null && !b.removed && !b.isSpectator() && !WarFeatures.isInvolved(b)
                && !b.level.dimension().equals(WarArena.DIMENSION) : eligible(b);
        if (!eligible(a) || !bOk || a.level != b.level || !teammates(a, b) || !moonUp(a)) return false;
        double r = Settings.server().d("thread.bondRange");
        if (a.distanceToSqr(b) > r * r) return false;
        return !Settings.server().b("thread.requireLineOfSight") || lineOfSight(a, b);
    }

    /** Diagnostics: which part of the direct-connection rule holds. */
    public static String explain(ServerPlayerEntity a, ServerPlayerEntity b) {
        double r = Settings.server().d("thread.bondRange");
        return "eligA=" + eligible(a) + " eligB=" + eligible(b) + " sameLevel=" + (a.level == b.level) + " teams=" + team(a) + "/" + team(b)
                + " moon=" + moonUp(a) + " dist2=" + a.distanceToSqr(b) + "/" + r * r + " los=" + lineOfSight(a, b)
                + " alive=" + a.isAlive() + "/" + b.isAlive() + " spec=" + a.isSpectator() + " war=" + WarFeatures.isInvolved(a);
    }

    /** Does a's look ray pass through b's hitbox (with a small tolerance) within range? */
    public static boolean aimingAt(ServerPlayerEntity a, Entity b, double range, double tolerance) {
        Vector3d eye = a.getEyePosition(1f);
        AxisAlignedBB box = b.getBoundingBox().inflate(tolerance);
        if (box.contains(eye)) return true;
        Vector3d end = eye.add(a.getViewVector(1f).scale(range + 1));
        return box.clip(eye, end).isPresent() && box.getCenter().distanceTo(eye) <= range + 1.5;
    }

    /** Entity position from its coordinates (never Entity.position(): fake players report 0,0,0 there). */
    public static Vector3d pos(Entity e) {
        return new Vector3d(e.getX(), e.getY(), e.getZ());
    }

    /** Flag for health moved by the Thread Moon itself: no Thread rule (or retaliation) reacts to it. */
    static int internal;

    public static boolean internal() {
        return internal > 0;
    }

    /** Set health as an internal Thread transaction (Lifeline payment, Guard payment, Last Stitch). */
    static float pay(ServerPlayerEntity p, float amount) {
        float before = p.getHealth();
        internal++;
        try {
            p.setHealth(before - amount);
        } finally {
            internal--;
        }
        return before - p.getHealth();
    }
}
