package net.ecmoons.feature.reveal;

import net.ecmoons.config.Settings;
import net.ecmoons.feature.RevealingFeatures;
import net.ecmoons.net.InspectPacket;
import net.ecmoons.net.Network;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.potion.EffectInstance;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read Your Enemy: a revealed player who keeps their aim on another eligible player (same dimension, within range,
 * ordinary line of sight) for aimSeconds receives that player's health, maximum health, absorption and active
 * effects, refreshed updatesPerSecond times a second. Nothing else is ever sent.
 * <p>
 * The client only proposes which player it is aiming at (InspectAimPacket); the server checks everything itself
 * every tick - the observer's own reveal, both players' eligibility, the dimension, the range, that the observer's
 * look ray actually passes through the target, and line of sight - and resets the acquisition the moment any of
 * it fails (then tells the client to hide the panel). A client can therefore never query an arbitrary player.
 */
public final class ReadYourEnemy {
    private static final class Aim {
        final int targetId;
        int validTicks;
        long lastSent = Long.MIN_VALUE;
        boolean acquired;

        Aim(int targetId) {
            this.targetId = targetId;
        }
    }

    public enum Check { OK, DISABLED, NOT_REVEALED, OBSERVER_INELIGIBLE, NO_TARGET, TARGET_INELIGIBLE, OTHER_DIMENSION, OUT_OF_RANGE, NOT_AIMED, NO_LINE_OF_SIGHT }

    private static final Map<UUID, Aim> AIMS = new HashMap<>();
    /** Info packets sent since start (diagnostics / tests). */
    public static int sent;
    /** Last packet built per observer (tests; FakePlayers have no connection to receive it). */
    public static final Map<UUID, InspectPacket> LAST = new HashMap<>();
    /** Self test only: fake players are not in the world's entity list, so they are looked up here. */
    public static final Map<Integer, ServerPlayerEntity> TEST_TARGETS = new HashMap<>();

    private ReadYourEnemy() {}

    /** The client says which player (entity id, or -1 for none) it is aiming at. */
    public static void onAim(ServerPlayerEntity observer, int targetId) {
        Aim a = AIMS.get(observer.getUUID());
        if (targetId < 0 || !RevealState.isRevealed(observer) || !Settings.server().b("revealing_inspect.enabled")) {
            forget(observer, a != null && a.acquired);
            return;
        }
        if (a != null && a.targetId == targetId) return;
        if (a != null && a.acquired) send(observer, InspectPacket.clear());
        AIMS.put(observer.getUUID(), new Aim(targetId));
    }

    /** Every tick for each observer who has proposed a target. */
    public static void tick(List<ServerPlayerEntity> players, long now) {
        if (AIMS.isEmpty()) return;
        Settings s = Settings.server();
        int need = Math.max(1, (int) Math.round(s.d("revealing_inspect.aimSeconds") * 20));
        int interval = Math.max(2, 20 / Math.max(1, s.i("revealing_inspect.updatesPerSecond")));
        for (ServerPlayerEntity observer : players) {
            Aim a = AIMS.get(observer.getUUID());
            if (a == null) continue;
            Entity e = observer.getLevel().getEntity(a.targetId);
            if (e == null) e = TEST_TARGETS.get(a.targetId);
            Check c = check(observer, e instanceof ServerPlayerEntity ? (ServerPlayerEntity) e : null);
            if (c != Check.OK) {
                a.validTicks = 0;
                if (a.acquired) {
                    a.acquired = false;
                    send(observer, InspectPacket.clear());
                }
                if (c == Check.NOT_REVEALED || c == Check.OBSERVER_INELIGIBLE || c == Check.DISABLED) AIMS.remove(observer.getUUID());
                continue;
            }
            if (a.validTicks < need) a.validTicks++;
            if (a.validTicks >= need) {
                a.acquired = true;
                if (now - a.lastSent >= interval) {
                    a.lastSent = now;
                    send(observer, build((ServerPlayerEntity) e));
                }
            }
        }
    }

    /** All the server-side conditions, in order. */
    public static Check check(ServerPlayerEntity observer, @Nullable ServerPlayerEntity target) {
        Settings s = Settings.server();
        if (!s.b("revealing_inspect.enabled")) return Check.DISABLED;
        if (!RevealState.isRevealed(observer)) return Check.NOT_REVEALED;
        if (RevealingFeatures.hidden(observer)) return Check.OBSERVER_INELIGIBLE;
        if (target == null || target == observer || !target.isAlive()) return Check.NO_TARGET;
        if (RevealingFeatures.hidden(target)) return Check.TARGET_INELIGIBLE;
        if (target.level != observer.level) return Check.OTHER_DIMENSION;
        double range = s.d("revealing_inspect.range");
        Vector3d eye = observer.getEyePosition(1f);
        AxisAlignedBB box = target.getBoundingBox();
        if (box.getCenter().distanceToSqr(eye) > range * range) return Check.OUT_OF_RANGE;
        // the look ray must pass through the target (a little tolerance for latency between the client's aim and
        // the server's copy of the player's rotation)
        Vector3d end = eye.add(observer.getViewVector(1f).scale(range + 2));
        if (!box.inflate(s.d("revealing_inspect.aimTolerance")).clip(eye, end).isPresent() && !box.contains(eye)) return Check.NOT_AIMED;
        if (!observer.canSee(target)) return Check.NO_LINE_OF_SIGHT;
        return Check.OK;
    }

    static InspectPacket build(ServerPlayerEntity target) {
        int max = Math.max(1, Settings.server().i("revealing_inspect.maxEffectsShown"));
        List<EffectInstance> effects = new ArrayList<>(target.getActiveEffects());
        effects.sort((x, y) -> Integer.compare(y.getDuration(), x.getDuration()));
        List<InspectPacket.Effect> list = new ArrayList<>();
        for (int i = 0; i < effects.size() && i < max; i++) {
            EffectInstance ei = effects.get(i);
            if (ei.getEffect().getRegistryName() == null) continue;
            list.add(new InspectPacket.Effect(ei.getEffect().getRegistryName(), ei.getAmplifier(), ei.getDuration()));
        }
        return new InspectPacket(target.getId(), target.getDisplayName().getString(), target.getHealth(), target.getMaxHealth(),
                target.getAbsorptionAmount(), list, Math.max(0, effects.size() - list.size()));
    }

    private static void send(ServerPlayerEntity observer, InspectPacket packet) {
        if (packet.targetId >= 0) sent++;
        LAST.put(observer.getUUID(), packet);
        Network.send(observer, packet);
    }

    public static boolean acquired(ServerPlayerEntity observer) {
        Aim a = AIMS.get(observer.getUUID());
        return a != null && a.acquired;
    }

    /** Drop this observer's aim; tell the client to hide the panel when one may be showing. */
    public static void forget(ServerPlayerEntity observer, boolean tellClient) {
        Aim a = AIMS.remove(observer.getUUID());
        if (tellClient && (a == null || a.acquired)) send(observer, InspectPacket.clear());
    }

    public static void forget(UUID id) {
        AIMS.remove(id);
        LAST.remove(id);
    }

    public static void clear() {
        AIMS.clear();
        LAST.clear();
    }
}
