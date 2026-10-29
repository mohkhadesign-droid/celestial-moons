package net.ecmoons.feature.reveal;

import net.ecmoons.config.Settings;
import net.ecmoons.feature.RevealingFeatures;
import net.ecmoons.net.Network;
import net.ecmoons.net.RevealPacket;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.server.ServerWorld;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Binding Gaze: during the eye's active (fully open) scan only, an eligible player whose view stays inside a narrow
 * cone around the rendered moon, with open sky in that direction, for stareSeconds is bound: the client stops
 * voluntary walking, sprinting and jumping (camera, attacks, blocking and item use stay free). Nothing else is
 * touched - position, falling speed, knockback and fall damage are untouched and no potion effect is applied.
 * <p>
 * The server decides: it measures the gaze from the player's own look direction (identical in first and third
 * person) every tick, binds and releases, and tells the client. Looking away, the scan completing, the moon ending,
 * changing dimension, death and becoming ineligible all release at once. A teleport simply changes what the next
 * tick measures; the player is never moved back.
 */
public final class BindingGaze {
    private static final class Gaze {
        int ticks;
        boolean bound;
        int toldScan = -1;
    }

    private static final Map<UUID, Gaze> GAZE = new HashMap<>();
    /** Binds started since the server started (diagnostics / tests). */
    public static int bindsStarted;

    private BindingGaze() {}

    public static boolean enabled() {
        return Settings.server().b("revealing_binding.enabled");
    }

    /** Every tick of the active scan phase, for each player in the world. */
    public static void tickActive(ServerWorld world, List<ServerPlayerEntity> players, int scanId) {
        if (!enabled()) {
            releaseAll(players);
            return;
        }
        Settings s = Settings.server();
        double cone = s.d("revealing_binding.coneDegrees");
        int need = Math.max(1, (int) Math.round(s.d("revealing_binding.stareSeconds") * 20));
        Vector3d moon = MoonGaze.moonDirection(world, 1f);
        for (ServerPlayerEntity p : players) {
            boolean ok = !RevealingFeatures.hidden(p) && gazing(p, moon, cone);
            step(p, ok, need, scanId);
        }
    }

    /** One tick of the stare counter (separate so the self test can drive it with its own gaze result). */
    public static void step(ServerPlayerEntity p, boolean gazing, int need, int scanId) {
        Gaze g = GAZE.computeIfAbsent(p.getUUID(), k -> new Gaze());
        if (!gazing) {
            g.ticks = 0;
            if (g.bound) release(p, true);
            return;
        }
        if (g.ticks < need) g.ticks++;
        if (!g.bound && g.ticks >= need) {
            g.bound = true;
            bindsStarted++;
            Network.send(p, RevealPacket.bind(true, scanId));
            if (g.toldScan != scanId) { // one instruction per scan, never repeated chat
                g.toldScan = scanId;
                p.displayClientMessage(new StringTextComponent(Settings.server().s("revealing_binding.message"))
                        .withStyle(TextFormatting.GOLD), true);
            }
        }
    }

    /** Direct gaze: look within the cone of the moon's centre and open sky (no roof, not underground) that way. */
    public static boolean gazing(ServerPlayerEntity p, Vector3d moon, double cone) {
        if (!MoonGaze.lookingAtMoon(p, moon, cone)) return false;
        return MoonGaze.skyOpen(p.level, p.getEyePosition(1f), moon, 640);
    }

    public static boolean isBound(ServerPlayerEntity p) {
        Gaze g = GAZE.get(p.getUUID());
        return g != null && g.bound;
    }

    public static int stareTicks(ServerPlayerEntity p) {
        Gaze g = GAZE.get(p.getUUID());
        return g == null ? 0 : g.ticks;
    }

    /** Release (and reset the build-up). Safe to call for players who are not bound. */
    public static void release(ServerPlayerEntity p, boolean keepEntry) {
        Gaze g = keepEntry ? GAZE.get(p.getUUID()) : GAZE.remove(p.getUUID());
        if (g == null) return;
        g.ticks = 0;
        if (g.bound) {
            g.bound = false;
            Network.send(p, RevealPacket.bind(false, -1));
        }
    }

    /** Scan completed / moon ended: release everyone and forget the build-up. */
    public static void releaseAll(List<ServerPlayerEntity> players) {
        for (ServerPlayerEntity p : players) release(p, false);
    }

    public static void forget(UUID id) {
        GAZE.remove(id);
    }

    public static void clear() {
        GAZE.clear();
    }
}
