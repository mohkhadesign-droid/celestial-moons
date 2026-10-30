package net.ecmoons.feature.thread;

import net.ecmoons.MoonType;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.ThreadFeatures;
import net.ecmoons.feature.ability.MoonAbilities;
import net.ecmoons.net.ThreadInputPacket;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Thread Moon hub: ticks (passive bonds and guard state five times a second, Lifeline/Knotbind/reactions every tick),
 * key input, the damage and death hooks, and one cleanup path for the moon ending (natural end, cancellation and
 * /ecmoons end all arrive here the same way).
 */
public final class ThreadMoon {
    private static final Set<RegistryKey<World>> UP = new HashSet<>();
    /** True while Knotbind posts its attack probes (they must not count as the caster attacking). */
    static boolean probing;

    private ThreadMoon() {}

    public static long now() {
        return MoonAbilities.now();
    }

    /** Every tick for every server world. */
    public static void tick(ServerWorld w, MoonType active) {
        long now = now();
        boolean up = active == MoonType.THREAD;
        if (up || UP.contains(w.dimension())) ThreadEvent.get(w).update(w, up);
        if (up) UP.add(w.dimension());
        else if (UP.remove(w.dimension())) endAll(w);
        int interval = Math.max(1, Settings.server().i("thread.connectionIntervalTicks"));
        List<ServerPlayerEntity> players = w.players();
        if (now % interval == 0) {
            ThreadFeatures.tick(w, active, interval);
            if (up) GuardThread.tick(players, now);
            ThreadNet.sendStates(w, now, up);
        }
        if (!up) return;
        Lifeline.tick(players, now);
        Knotbind.tick(w, now);
        ThreadReactions.tick(w);
        if (now % 5 == 0 && !Knotbind.knots().isEmpty()) ThreadNet.knotsChanged(w);
    }

    /** The moon ended here: every event-owned temporary state goes (Last Stitch usage stays with its event id). */
    static void endAll(ServerWorld w) {
        Lifeline.endAll();
        GuardThread.endAll();
        Knotbind.endAll(w);
        ThreadReactions.end(w);
        ThreadNet.knotsChanged(w);
        ThreadNet.sendStates(w, now(), false);
    }

    // ---- input ---------------------------------------------------------------------------------------------------

    public static void onInput(ServerPlayerEntity p, byte action, int target) {
        long now = now();
        if (action == ThreadInputPacket.UNRAVEL_START || action == ThreadInputPacket.UNRAVEL_STOP) {
            Knotbind.unravel(p, action == ThreadInputPacket.UNRAVEL_START, now);
            ThreadNet.dirty(p);
            return;
        }
        if (action == ThreadInputPacket.UNRAVEL_RESET) {
            Knotbind.resetUnravel(p, now);
            return;
        }
        if (action == ThreadInputPacket.LIFELINE_STOP) {
            Lifeline.stop(p, now, "released");
            ThreadNet.dirty(p);
            return;
        }
        String msg = null;
        if (action == ThreadInputPacket.LIFELINE_START) {
            msg = Lifeline.start(p, target, now);
        } else if (action == ThreadInputPacket.GUARD_TOGGLE) {
            msg = GuardThread.toggle(p, target, now);
        } else if (action == ThreadInputPacket.KNOT_FIRE) {
            if (Knotbind.victim(p)) return;
            msg = Knotbind.fire(p);
        }
        if (msg != null) ThreadNet.notice(p, msg);
        ThreadNet.dirty(p);
    }

    // ---- hooks ---------------------------------------------------------------------------------------------------

    /** After armour, absorption and the passive reduction: Guard Thread's split; any real damage stops a donor. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent event) {
        if (!(event.getEntityLiving() instanceof ServerPlayerEntity) || ThreadLinks.internal()) return;
        ServerPlayerEntity p = (ServerPlayerEntity) event.getEntityLiving();
        if (!ThreadLinks.moonUp(p) || event.getAmount() <= 0f) return;
        long now = now();
        Lifeline.onDonorHurt(p, now);
        event.setAmount(GuardThread.split(p, event.getAmount(), event.getSource(), now));
    }

    /** Last Stitch: after totems and other death prevention (a death they stopped never gets here). */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntityLiving() instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity p = (ServerPlayerEntity) event.getEntityLiving();
        if (!ThreadLinks.moonUp(p)) return;
        if (GuardThread.lastStitch(p, event.getSource(), now())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (probing || !(event.getPlayer() instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity p = (ServerPlayerEntity) event.getPlayer();
        Lifeline.onAction(p, now(), "attacked");
        Knotbind.resetUnravel(p, now());
    }

    @SubscribeEvent
    public static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getPlayer() instanceof ServerPlayerEntity)) return;
        Lifeline.onAction((ServerPlayerEntity) event.getPlayer(), now(), "used an item");
        Knotbind.resetUnravel(event.getPlayer(), now());
    }

    @SubscribeEvent
    public static void onUseStart(LivingEntityUseItemEvent.Start event) {
        if (!(event.getEntityLiving() instanceof ServerPlayerEntity)) return;
        Lifeline.onAction((ServerPlayerEntity) event.getEntityLiving(), now(), "used an item");
        Knotbind.resetUnravel(event.getEntityLiving(), now());
    }

    /** Death, logout or dimension change: this player's temporary Thread state ends (Last Stitch usage is kept). */
    public static void forget(ServerPlayerEntity p) {
        Lifeline.onAction(p, now(), "left");
        Lifeline.forget(p.getUUID());
        GuardThread.forget(p.getUUID());
        Knotbind.forget(p);
        ThreadNet.forget(p.getUUID());
    }

    /** A player arrived in a world: what the moon cat is doing right now (state packets follow on their own). */
    public static void sync(ServerPlayerEntity p) {
        ThreadReactions.sync(p);
        ThreadNet.dirty(p);
    }

    public static void clear() {
        UP.clear();
        ThreadFeatures.clear();
        Lifeline.clear();
        GuardThread.clearAll();
        Knotbind.clear();
        ThreadReactions.clear();
        ThreadNet.clear();
    }
}
