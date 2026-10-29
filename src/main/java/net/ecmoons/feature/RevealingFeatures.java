package net.ecmoons.feature;

import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.reveal.BindingGaze;
import net.ecmoons.feature.reveal.ReadYourEnemy;
import net.ecmoons.feature.reveal.RevealState;
import net.ecmoons.feature.reveal.WorldNotices;
import net.ecmoons.feature.war.WarFeatures;
import net.ecmoons.net.Network;
import net.ecmoons.net.RevealPacket;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Revealing Moon. The server owns the timing, the scan ids and every per-player state. Each cycle:
 * <ol>
 *   <li>idle: the eye watches (local idle blinks and wandering pupil);</li>
 *   <li>warning / opening ("The eye is searching..." + countdown): the eye narrows, then opens wide, glow building;</li>
 *   <li>active scanning: the last activeScanSeconds of the countdown, fully open with its strong glow - Binding
 *       Gaze is only possible here;</li>
 *   <li>completion (the end of the countdown, exactly when the old scan happened): ONE golden sky ripple for the whole
 *       dimension, the existing reveal (markers within revealRadius), each eligible player's own reveal timer
 *       (True Exposure, Read Your Enemy, amber edges and colour loss), nearby hostile investigations, every gaze
 *       bind released, and the eye begins to close;</li>
 *   <li>each player's reveal ends on their own timer; the next cycle follows at the configured cadence.</li>
 * </ol>
 * The cadence (scanIntervalSeconds) and the countdown are unchanged: the active phase is carved out of the end of
 * the countdown, so completion happens at the same moment the scan always did. Clients only animate and draw.
 * <p>
 * Never revealed: spectators, creative players (unless includeCreative), players tagged ecmoons_reveal_exempt,
 * players inside a configured protected area and War Moon participants. Claims are not protected automatically.
 * Markers are placed at the surface above the player (so an underground player only gives away their horizontal
 * position); a roof does not protect. The same {@link #hidden} rule decides every new mechanic.
 */
public final class RevealingFeatures {
    public static final String EXEMPT_TAG = "ecmoons_reveal_exempt";

    private static final class Cycle {
        long nextWarn;
        long scanAt = -1;
        int scanId;
        long warnGameTime = -1, lastCompleteGameTime = -1;
        int opening, countdown;
    }

    private static final Map<RegistryKey<World>, Cycle> CYCLES = new HashMap<>();
    private static int nextScanId = 1;

    private RevealingFeatures() {}

    /** Called every tick for every server world. */
    public static void tick(ServerWorld world, MoonType active) {
        long now = world.getServer().getTickCount();
        Cycle c = CYCLES.get(world.dimension());
        if (active != MoonType.REVEALING) {
            if (c != null) {
                CYCLES.remove(world.dimension());
                stop(world);
            }
            return;
        }
        Settings s = Settings.server();
        int countdown = s.i("revealing.countdownSeconds") * 20;
        int interval = Math.max(s.i("revealing.scanIntervalSeconds") * 20, countdown + 40);
        if (c == null) {
            c = new Cycle();
            c.nextWarn = now + interval - countdown;
            CYCLES.put(world.dimension(), c);
        }
        if (c.scanAt < 0 && now >= c.nextWarn) {
            c.scanAt = now + countdown;
            c.nextWarn = c.scanAt + interval - countdown;
            warn(world, c, countdown);
        }
        List<ServerPlayerEntity> players = world.players();
        if (c.scanAt >= 0) {
            long left = c.scanAt - now;
            boolean activePhase = left > 0 && left <= c.countdown - c.opening;
            if (activePhase) BindingGaze.tickActive(world, players, c.scanId);
            if (left > 0 && left % 20 == 0) {
                StringTextComponent msg = new StringTextComponent(left > c.countdown - c.opening
                        ? "The eye opens in " + ((left - (c.countdown - c.opening) + 19) / 20) + "…"
                        : "The eye is wide open… " + (left / 20));
                msg.withStyle(TextFormatting.GOLD);
                for (ServerPlayerEntity p : players) {
                    if (!BindingGaze.isBound(p)) p.displayClientMessage(msg, true); // never overwrite the bind instruction
                }
            }
            if (left <= 0) {
                c.scanAt = -1;
                complete(world, c, false, null);
            }
        }
        RevealState.tick(players, now);
        ReadYourEnemy.tick(players, now);
        if (now % 5 == 0) WorldNotices.tick(world, now);
    }

    /** Opening and active lengths within the countdown (the active phase is at most countdown - 1 s). */
    public static int activeTicks(int countdown) {
        int want = (int) Math.round(Settings.server().d("revealing.activeScanSeconds") * 20);
        return Math.max(0, Math.min(want, countdown - 20));
    }

    private static void warn(ServerWorld world, Cycle c, int countdownTicks) {
        c.scanId = nextScanId++;
        c.countdown = countdownTicks;
        c.opening = countdownTicks - activeTicks(countdownTicks);
        c.warnGameTime = world.getGameTime();
        RevealPacket packet = RevealPacket.warn(c.scanId, c.warnGameTime, c.opening, c.countdown);
        StringTextComponent msg = new StringTextComponent("The eye is searching…");
        msg.withStyle(TextFormatting.GOLD, TextFormatting.ITALIC);
        for (ServerPlayerEntity p : world.players()) {
            Network.send(p, packet);
            p.sendMessage(msg, net.minecraft.util.Util.NIL_UUID);
        }
    }

    /** The moon ended, was cancelled or stopped by an admin: one cleanup path for every case. */
    private static void stop(ServerWorld world) {
        List<ServerPlayerEntity> players = world.players();
        RevealState.endAll(players, world.dimension(), RevealState.EndReason.MOON_ENDED);
        WorldNotices.endAll(world);
        RevealPacket packet = new RevealPacket(RevealPacket.STOP, 0, Collections.emptyList());
        for (ServerPlayerEntity p : players) Network.send(p, packet);
    }

    /** A player joined, respawned or arrived in this world mid-moon: send the current cycle (never a replay). */
    public static void sync(ServerPlayerEntity p) {
        Cycle c = CYCLES.get(p.level.dimension());
        if (c == null) return;
        boolean counting = c.scanAt >= 0;
        Network.send(p, RevealPacket.sync(c.scanId, counting ? c.warnGameTime : -1L, c.opening, c.countdown, c.lastCompleteGameTime));
    }

    /** Current phase name for this world (status command / tests): idle, opening, active. */
    public static String phase(ServerWorld world) {
        Cycle c = CYCLES.get(world.dimension());
        if (c == null) return "none";
        if (c.scanAt < 0) return "idle";
        long left = c.scanAt - world.getServer().getTickCount();
        return left > c.countdown - c.opening ? "opening" : "active";
    }

    public static int scanId(ServerWorld world) {
        Cycle c = CYCLES.get(world.dimension());
        return c == null ? -1 : c.scanId;
    }

    /** True when this player is never revealed. */
    public static boolean hidden(ServerPlayerEntity p) {
        if (!p.isAlive() || p.isSpectator()) return true;
        if (p.isCreative() && !Settings.server().b("revealing.includeCreative")) return true;
        if (p.getTags().contains(EXEMPT_TAG)) return true;
        if (WarFeatures.isInvolved(p)) return true;
        return inProtectedArea(p);
    }

    private static boolean inProtectedArea(ServerPlayerEntity p) {
        String dim = p.level.dimension().location().toString();
        for (String entry : Settings.server().list("revealing.protectedAreas")) {
            try {
                String[] parts = entry.trim().split("\\|");
                if (parts.length != 3 || !parts[0].trim().equals(dim)) continue;
                String[] a = parts[1].split(","), b = parts[2].split(",");
                double x1 = Double.parseDouble(a[0].trim()), z1 = Double.parseDouble(a[1].trim());
                double x2 = Double.parseDouble(b[0].trim()), z2 = Double.parseDouble(b[1].trim());
                if (p.getX() >= Math.min(x1, x2) && p.getX() <= Math.max(x1, x2) + 1
                        && p.getZ() >= Math.min(z1, z2) && p.getZ() <= Math.max(z1, z2) + 1) return true;
            } catch (RuntimeException e) {
                EcMoons.LOGGER.warn("Invalid revealing.protectedAreas entry '{}' (expected dimension|x1,z1|x2,z2)", entry);
            }
        }
        return false;
    }

    /** Where the marker for this player goes: their position, lifted to the surface when they are below it. */
    public static Vector3d markerPos(ServerPlayerEntity p) {
        ServerWorld w = p.getLevel();
        int surface = w.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(p.getX()), (int) Math.floor(p.getZ()));
        double y = p.getY() >= surface - 0.5 ? p.getY() : surface;
        return new Vector3d(p.getX(), y, p.getZ());
    }

    /**
     * Immediate scan completion (admin command / tests): same as the cycle's completion. Returns the number of
     * players revealed.
     */
    public static int scan(ServerWorld world, boolean quiet, @Nullable ServerPlayerEntity includeSelfFor) {
        Cycle c = CYCLES.get(world.dimension());
        if (c == null) {
            c = new Cycle();
            c.nextWarn = Long.MAX_VALUE; // no cycle without the moon; an admin scan stands alone
        }
        if (c.scanAt >= 0) c.scanAt = -1; // a running countdown is completed now, not twice
        else c.scanId = nextScanId++;
        return complete(world, c, quiet, includeSelfFor);
    }

    /**
     * Scan completion: record every eligible player, send each viewer the markers within revealRadius (never their
     * own unless includeSelfFor is that viewer - test command), start one shared ripple, start each revealed
     * player's own timer, assign investigations and release all gaze binds.
     */
    private static int complete(ServerWorld world, Cycle c, boolean quiet, @Nullable ServerPlayerEntity includeSelfFor) {
        Settings s = Settings.server();
        double radius = s.i("revealing.revealRadius");
        int ticks = s.i("revealing.markerSeconds") * 20;
        long now = world.getServer().getTickCount();
        c.lastCompleteGameTime = world.getGameTime();
        List<ServerPlayerEntity> players = world.players();
        BindingGaze.releaseAll(players); // the scan is over: every bind ends here
        List<ServerPlayerEntity> targets = new ArrayList<>();
        List<Vector3d> positions = new ArrayList<>();
        for (ServerPlayerEntity p : players) {
            if (hidden(p) && p != includeSelfFor) continue;
            targets.add(p);
            positions.add(markerPos(p));
        }
        for (ServerPlayerEntity viewer : players) {
            List<Vector3d> mine = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                if (targets.get(i) == viewer && viewer != includeSelfFor) continue;
                Vector3d m = positions.get(i);
                double dx = m.x - viewer.getX(), dz = m.z - viewer.getZ();
                if (dx * dx + dz * dz <= radius * radius) mine.add(m);
            }
            Network.send(viewer, RevealPacket.scan(c.scanId, c.lastCompleteGameTime, ticks, mine));
            if (!quiet) {
                world.playSound(null, viewer.getX(), viewer.getY(), viewer.getZ(), SoundEvents.ENDER_EYE_DEATH, SoundCategory.AMBIENT, 0.6F, 0.6F);
                viewer.displayClientMessage(new StringTextComponent("The eye has seen " + mine.size()
                        + (mine.size() == 1 ? " player" : " players") + " nearby").withStyle(TextFormatting.GOLD), true);
            }
        }
        // each revealed player's own timer (refreshing never stacks), in a fixed order for the investigations
        List<ServerPlayerEntity> revealed = new ArrayList<>(targets);
        revealed.sort(Comparator.comparing(ServerPlayerEntity::getUUID));
        int duration = RevealState.durationTicks();
        for (ServerPlayerEntity p : revealed) RevealState.reveal(p, c.scanId, now, duration);
        WorldNotices.onScan(world, revealed, now);
        lastScanId = c.scanId;
        return targets.size();
    }

    /** Id of the most recent completed scan (tests). */
    public static int lastScanId;

    /** Preview command: one blink for this player only. */
    public static void blink(ServerPlayerEntity p) {
        Network.send(p, new RevealPacket(RevealPacket.BLINK, 0, Collections.emptyList()));
    }

    /** Start the next cycle now (warning + countdown), as the timer would. */
    public static boolean warnNow(ServerWorld world) {
        Cycle c = CYCLES.get(world.dimension());
        if (c == null) return false;
        c.nextWarn = world.getServer().getTickCount();
        c.scanAt = -1;
        return true;
    }

    public static String describe(ServerWorld world) {
        Cycle c = CYCLES.get(world.dimension());
        if (c == null) return "no Revealing Moon cycle in this dimension";
        long now = world.getServer().getTickCount();
        String when = c.scanAt >= 0 ? phase(world) + ", scan " + c.scanId + " completes in " + Math.max(0, (c.scanAt - now) / 20) + "s"
                : "next warning in " + Math.max(0, (c.nextWarn - now) / 20) + "s";
        return when + "; revealed players " + RevealState.count() + ", investigations " + WorldNotices.active(world);
    }

    public static void clear() {
        CYCLES.clear();
        RevealState.clear();
        BindingGaze.clear();
        ReadYourEnemy.clear();
        WorldNotices.clear();
    }
}
