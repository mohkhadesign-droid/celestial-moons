package net.ecmoons.feature.ability;

import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.feature.war.WarArena;
import net.ecmoons.feature.war.WarFeatures;
import net.ecmoons.net.AbilityPacket;
import net.ecmoons.net.Network;
import net.ecmoons.net.AbilityStatePacket;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The single "Moon Ability" key. Which ability it triggers depends only on the moon that is up in the player's
 * world; the server decides everything (timing in server ticks, distances, collisions, cooldowns, permissions).
 * Abilities never work inside the War Moon arena or for players taking part in a War Moon tournament.
 */
public final class MoonAbilities {
    private static final Map<UUID, Long> COOLDOWN_AFTERIMAGE = new HashMap<>(), COOLDOWN_SHELL = new HashMap<>();

    private MoonAbilities() {}

    public static long now() {
        return ServerLifecycleHooks.getCurrentServer() == null ? 0 : ServerLifecycleHooks.getCurrentServer().getTickCount();
    }

    public static void fail(ServerPlayerEntity p, String msg) {
        p.displayClientMessage(new StringTextComponent(msg).withStyle(TextFormatting.RED), true);
    }

    /** Common gate. Returns a reason, or null if the player may use this moon's ability right now. */
    public static String blocked(ServerPlayerEntity p, MoonType type) {
        if (!Moons.is(p.level, type)) return "No moon ability tonight";
        if (!p.isAlive() || p.isSpectator() || p.isSleeping()) return "You can't use that now";
        if (p.level.dimension().equals(WarArena.DIMENSION) || WarFeatures.isInvolved(p)) return "Moon abilities don't work in the War Moon arena";
        return null;
    }

    public static void onKey(ServerPlayerEntity p, byte action, float dirX, float dirZ) {
        MoonType type = Moons.active(p.level);
        if (action == AbilityPacket.RELEASE) {
            ShellAbility.onRelease(p);
            return;
        }
        if (type == MoonType.AFTERIMAGE) AfterimageAbility.onPress(p, dirX, dirZ);
        else if (type == MoonType.SHELL) ShellAbility.onPress(p);
        else fail(p, "No moon ability tonight");
    }

    // ---- cooldowns ----------------------------------------------------------------------------------------------

    private static Map<UUID, Long> map(MoonType t) {
        return t == MoonType.SHELL ? COOLDOWN_SHELL : COOLDOWN_AFTERIMAGE;
    }

    public static int cooldownLeft(PlayerEntity p, MoonType t) {
        Long until = map(t).get(p.getUUID());
        return until == null ? 0 : (int) Math.max(0, until - now());
    }

    public static void startCooldown(PlayerEntity p, MoonType t, int seconds) {
        if (seconds > 0) map(t).put(p.getUUID(), now() + seconds * 20L);
    }

    public static void startCooldownFor(UUID player, MoonType t, int seconds) {
        if (seconds > 0) map(t).put(player, now() + seconds * 20L);
    }

    /** Admin: clear cooldowns and any running sequence/shell of this player. */
    public static void reset(ServerPlayerEntity p) {
        COOLDOWN_AFTERIMAGE.remove(p.getUUID());
        COOLDOWN_SHELL.remove(p.getUUID());
        AfterimageAbility.cancel(p, false);
        ShellAbility.collapseOwned(p, "reset");
        sendIdle(p);
    }

    static void sendIdle(ServerPlayerEntity p) {
        Network.send(p, new AbilityStatePacket((byte) 0, 0, 0, 0, 0));
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        AfterimageAbility.tick();
        ShellAbility.tickEnd();
    }

    @SubscribeEvent
    public static void onWorldTickStart(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.START && !event.world.isClientSide) ShellAbility.interceptBeforeMove(event.world);
    }

    private static void cleanup(PlayerEntity player, String why) {
        if (!(player instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity p = (ServerPlayerEntity) player;
        AfterimageAbility.cancel(p, false);
        ShellAbility.collapseOwned(p, why);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        cleanup(event.getPlayer(), "logout");
    }

    @SubscribeEvent
    public static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        cleanup(event.getPlayer(), "dimension change");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntityLiving() instanceof PlayerEntity) cleanup((PlayerEntity) event.getEntityLiving(), "death");
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getPlayer() instanceof ServerPlayerEntity) AfterimageAbility.clearPenalty((ServerPlayerEntity) event.getPlayer());
    }

    public static void onServerStopped() {
        COOLDOWN_AFTERIMAGE.clear();
        COOLDOWN_SHELL.clear();
        AfterimageAbility.clearAll();
        ShellAbility.clearAll();
    }
}
