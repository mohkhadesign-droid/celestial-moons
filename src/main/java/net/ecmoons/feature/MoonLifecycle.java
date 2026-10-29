package net.ecmoons.feature;

import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Matchers;
import net.ecmoons.config.Settings;
import net.ecmoons.net.Network;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.ecmoons.feature.war.WarFeatures;
import net.ecmoons.feature.reveal.BindingGaze;
import net.ecmoons.feature.reveal.ReadYourEnemy;
import net.ecmoons.feature.reveal.RevealState;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-tick bookkeeping, moon transitions and cleanup. All checks are per player or per world (no world scans).
 */
public final class MoonLifecycle {
    private static final Map<RegistryKey<World>, MoonType> LAST = new HashMap<>();

    private MoonLifecycle() {}

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.world instanceof ServerWorld)) return;
        ServerWorld world = (ServerWorld) event.world;
        MoonType now = Moons.active(world);
        MoonType before = LAST.put(world.dimension(), now);
        if (before != null && before != now) onTransition(world, before, now);
        RevealingFeatures.tick(world, now);
        if (world.getServer().getTickCount() % 10 == 0) ThreadFeatures.tick(world, now);
    }

    private static void onTransition(ServerWorld world, MoonType from, MoonType to) {
        EcMoons.LOGGER.info("[{}] add-on moon effects: {} -> {}", world.dimension().location(), from, to);
        if (to == MoonType.WAR) WarFeatures.onWarMoonRise(world);
        for (ServerPlayerEntity player : world.players()) {
            if (from == MoonType.FROST) FrostFeatures.clear(player);
            MistFeatures.updateNametag(player, to == MoonType.MIST);
            if (from == MoonType.GLUTTONY || to == MoonType.GLUTTONY) GluttonyFeatures.tick(player);
            // Drift: players still in the air keep their fall-damage grace until they land (DriftFeatures).
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity player = (ServerPlayerEntity) event.player;
        MoonType active = Moons.active(player.level);
        QuietFeatures.tick(player, active);
        DriftFeatures.tick(player, active);
        if (player.tickCount % FrostFeatures.CHECK_INTERVAL == 0) FrostFeatures.tick(player, active);
        if (player.tickCount % 20 == 0) {
            MistFeatures.updateNametag(player, active == MoonType.MIST);
            GluttonyFeatures.tick(player);
        }
    }

    @SubscribeEvent
    public static void onFoodEaten(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntityLiving() instanceof ServerPlayerEntity) || !event.getItem().isEdible()) return;
        ServerPlayerEntity player = (ServerPlayerEntity) event.getEntityLiving();
        if (Moons.is(player.level, MoonType.FROST)) FrostFeatures.onAte(player);
        if (Moons.is(player.level, MoonType.GLUTTONY)) GluttonyFeatures.onAte(player, event.getItem());
    }

    /** Gluttony progress ends with the player's life (cancelled deaths, e.g. War Moon knockouts, never get here). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntityLiving() instanceof ServerPlayerEntity) {
            ServerPlayerEntity player = (ServerPlayerEntity) event.getEntityLiving();
            GluttonyFeatures.onDeath(player);
            if (!event.isCanceled()) RevealState.end(player, RevealState.EndReason.DEATH);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getPlayer() instanceof ServerPlayerEntity) refresh((ServerPlayerEntity) event.getPlayer());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
        RevealState.end(player, RevealState.EndReason.DEATH);
        refresh(player);
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity player = (ServerPlayerEntity) event.getPlayer();
        RevealState.end(player, RevealState.EndReason.DIMENSION);
        refresh(player);
    }

    /** Re-sync settings and drop any state that belonged to the previous world/body. */
    private static void refresh(ServerPlayerEntity player) {
        Network.syncSettings(player);
        FrostFeatures.clear(player);
        MistFeatures.updateNametag(player, Moons.is(player.level, MoonType.MIST));
        GluttonyFeatures.tick(player);
        RevealingFeatures.sync(player); // current Revealing cycle only: no stale reveal, bind or ripple replay
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PlayerEntity player = event.getPlayer();
        FrostFeatures.forget(player.getUUID());
        QuietFeatures.forget(player.getUUID());
        DriftFeatures.forget(player.getUUID());
        ThreadFeatures.forget(player.getUUID());
        if (player instanceof ServerPlayerEntity) RevealState.forget((ServerPlayerEntity) player);
        BindingGaze.forget(player.getUUID());
        ReadYourEnemy.forget(player.getUUID());
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent.VanillaTagTypes event) {
        Matchers.invalidate();
    }

    public static void onServerStopped(FMLServerStoppedEvent event) {
        LAST.clear();
        RevealingFeatures.clear();
        ThreadFeatures.clear();
        net.ecmoons.feature.ability.MoonAbilities.onServerStopped();
    }

    /** Settings changed (config file edited or /ecmoons reload): rebuild caches and push to every client. */
    public static void settingsChanged(Iterable<ServerWorld> worlds) {
        Settings.reloadServer();
        Matchers.invalidate();
        if (worlds != null) Network.syncSettingsToAll(worlds);
    }
}
