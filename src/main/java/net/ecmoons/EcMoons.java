package net.ecmoons;

import net.ecmoons.client.ClientSetup;
import net.ecmoons.command.MoonCommands;
import net.ecmoons.config.ClientConfig;
import net.ecmoons.config.MoonConfig;
import net.ecmoons.event.DefaultEvents;
import net.ecmoons.feature.BloodMoonDrops;
import net.ecmoons.feature.BlueMoonLoot;
import net.ecmoons.feature.DriftFeatures;
import net.ecmoons.feature.MoonLifecycle;
import net.ecmoons.feature.PlacedBlocks;
import net.ecmoons.feature.ProspectorFeatures;
import net.ecmoons.feature.QuietFeatures;
import net.ecmoons.feature.SpawnOrigins;
import net.ecmoons.feature.WisdomFeatures;
import net.ecmoons.net.Network;
import net.ecmoons.selftest.SelfTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.loot.GlobalLootModifierSerializer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Enhanced Celestials: Ten Moons - an add-on for Enhanced Celestials 2.0.9 (Forge 1.16.5).
 * Enhanced Celestials keeps doing the scheduling, forecast, notifications, sky colour and network sync of lunar
 * events; this mod adds seven event types, extends the original three, and implements every moon's effects.
 */
@Mod(EcMoons.MOD_ID)
public class EcMoons {
    public static final String MOD_ID = "ecmoons";
    public static final Logger LOGGER = LogManager.getLogger("ecmoons");

    public EcMoons() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MoonConfig.SPEC, "ecmoons-common.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "ecmoons-client.toml");
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::onConfig);
        modBus.addGenericListener(GlobalLootModifierSerializer.class, this::registerLootModifiers);

        IEventBus forge = MinecraftForge.EVENT_BUS;
        forge.register(SpawnOrigins.class);
        forge.register(PlacedBlocks.class);
        forge.register(ProspectorFeatures.class);
        forge.register(WisdomFeatures.class);
        forge.register(QuietFeatures.class);
        forge.register(DriftFeatures.class);
        forge.register(MoonLifecycle.class);
        forge.register(net.ecmoons.feature.ability.MoonAbilities.class);
        forge.register(net.ecmoons.feature.ability.AfterimageAbility.class);
        forge.register(net.ecmoons.feature.ability.ShellAbility.class);
        forge.register(net.ecmoons.feature.ThreadFeatures.class);
        forge.register(net.ecmoons.feature.war.WarFeatures.class);
        forge.addListener(net.ecmoons.feature.war.WarFeatures::onServerStarted);
        forge.addGenericListener(Chunk.class, PlacedBlocks::attach);
        forge.addListener(this::registerCommands);
        forge.addListener(MoonLifecycle::onServerStopped);
        if (Boolean.getBoolean("ecmoons.selftest")) {
            forge.register(SelfTest.class);
            LOGGER.warn("ecmoons self test enabled: the server will run automated checks and then stop");
        }

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        Network.register();
        PlacedBlocks.registerCapability();
        // Enhanced Celestials' registries and the vanilla loot condition registry are not thread-safe.
        event.enqueueWork(() -> {
            DefaultEvents.register();
            BlueMoonLoot.registerCondition();
        });
    }

    private void onConfig(ModConfig.ModConfigEvent event) {
        if (event.getConfig().getSpec() != MoonConfig.SPEC) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            MoonLifecycle.settingsChanged(null);
        } else {
            server.execute(() -> MoonLifecycle.settingsChanged(server.getAllLevels()));
        }
    }

    private void registerLootModifiers(RegistryEvent.Register<GlobalLootModifierSerializer<?>> event) {
        event.getRegistry().register(new BloodMoonDrops.Serializer().setRegistryName(new ResourceLocation(MOD_ID, "blood_moon_rare_drops")));
        event.getRegistry().register(new ProspectorFeatures.BonusSerializer().setRegistryName(new ResourceLocation(MOD_ID, "prospector_bonus")));
    }

    private void registerCommands(RegisterCommandsEvent event) {
        MoonCommands.register(event.getDispatcher());
        net.ecmoons.command.NewMoonCommands.register(event.getDispatcher());
    }
}
