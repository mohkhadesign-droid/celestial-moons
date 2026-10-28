package net.ecmoons.feature;

import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.config.Settings;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Revealing Moon. The server owns the timing: every scanIntervalSeconds it warns ("The eye is searching..."),
 * counts down, then records where every eligible player is and sends each viewer the spectral eye markers within
 * revealRadius. Clients only animate the eye and draw the markers. No coordinates are ever printed in chat.
 * <p>
 * Never revealed: spectators, creative players (unless includeCreative), players tagged ecmoons_reveal_exempt,
 * players inside a configured protected area and War Moon participants. Claims are not protected automatically.
 * Markers are placed at the surface above the player (so an underground player only gives away their horizontal
 * position); a roof does not protect.
 */
public final class RevealingFeatures {
    public static final String EXEMPT_TAG = "ecmoons_reveal_exempt";

    private static final class Cycle {
        long nextWarn;
        long scanAt = -1;
    }

    private static final Map<RegistryKey<World>, Cycle> CYCLES = new HashMap<>();

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
            warn(world, countdown);
            c.scanAt = now + countdown;
            c.nextWarn = c.scanAt + interval - countdown;
        }
        if (c.scanAt >= 0) {
            long left = c.scanAt - now;
            if (left > 0 && left % 20 == 0) {
                StringTextComponent msg = new StringTextComponent("The eye opens in " + (left / 20) + "…");
                msg.withStyle(TextFormatting.GOLD);
                for (ServerPlayerEntity p : world.players()) p.displayClientMessage(msg, true);
            }
            if (left <= 0) {
                c.scanAt = -1;
                scan(world, false, null);
            }
        }
    }

    private static void warn(ServerWorld world, int countdownTicks) {
        RevealPacket packet = new RevealPacket(RevealPacket.WARN, countdownTicks, Collections.emptyList());
        StringTextComponent msg = new StringTextComponent("The eye is searching…");
        msg.withStyle(TextFormatting.GOLD, TextFormatting.ITALIC);
        for (ServerPlayerEntity p : world.players()) {
            Network.send(p, packet);
            p.sendMessage(msg, net.minecraft.util.Util.NIL_UUID);
        }
    }

    private static void stop(ServerWorld world) {
        RevealPacket packet = new RevealPacket(RevealPacket.STOP, 0, Collections.emptyList());
        for (ServerPlayerEntity p : world.players()) Network.send(p, packet);
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
     * Record every eligible player's position and send the markers. Each viewer gets the markers within revealRadius,
     * never their own (unless includeSelfFor is that viewer - test command). Returns the number of players revealed.
     */
    public static int scan(ServerWorld world, boolean quiet, @Nullable ServerPlayerEntity includeSelfFor) {
        Settings s = Settings.server();
        double radius = s.i("revealing.revealRadius");
        int ticks = s.i("revealing.markerSeconds") * 20;
        List<ServerPlayerEntity> targets = new ArrayList<>();
        List<Vector3d> positions = new ArrayList<>();
        for (ServerPlayerEntity p : world.players()) {
            if (hidden(p) && p != includeSelfFor) continue;
            targets.add(p);
            positions.add(markerPos(p));
        }
        for (ServerPlayerEntity viewer : world.players()) {
            List<Vector3d> mine = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                if (targets.get(i) == viewer && viewer != includeSelfFor) continue;
                Vector3d m = positions.get(i);
                double dx = m.x - viewer.getX(), dz = m.z - viewer.getZ();
                if (dx * dx + dz * dz <= radius * radius) mine.add(m);
            }
            Network.send(viewer, new RevealPacket(RevealPacket.SCAN, ticks, mine));
            if (!quiet) {
                world.playSound(null, viewer.getX(), viewer.getY(), viewer.getZ(), SoundEvents.ENDER_EYE_DEATH, SoundCategory.AMBIENT, 0.6F, 0.6F);
                viewer.displayClientMessage(new StringTextComponent("The eye has seen " + mine.size()
                        + (mine.size() == 1 ? " player" : " players") + " nearby").withStyle(TextFormatting.GOLD), true);
            }
        }
        return targets.size();
    }

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
        return c.scanAt >= 0 ? "scan in " + Math.max(0, (c.scanAt - now) / 20) + "s" : "next warning in " + Math.max(0, (c.nextWarn - now) / 20) + "s";
    }

    public static void clear() {
        CYCLES.clear();
    }
}
