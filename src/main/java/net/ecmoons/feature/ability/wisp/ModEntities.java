package net.ecmoons.feature.ability.wisp;

import net.ecmoons.EcMoons;
import net.minecraft.entity.EntityClassification;
import net.minecraft.entity.EntityType;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Wisp Moon's two small entities. Both are {@code noSave}: they are never written to disk, so a crash or restart
 * can't leave one behind, and they despawn with their chunk.
 */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITIES, EcMoons.MOD_ID);

    public static final RegistryObject<EntityType<WispEntity>> WISP = ENTITIES.register("wisp",
            () -> EntityType.Builder.<WispEntity>of(WispEntity::new, EntityClassification.MISC)
                    .sized(0.5F, 0.5F).noSave().fireImmune().clientTrackingRange(8).updateInterval(1)
                    .setCustomClientFactory((spawn, world) -> new WispEntity(ModEntities.WISP.get(), world))
                    .build(EcMoons.MOD_ID + ":wisp"));

    public static final RegistryObject<EntityType<ObserverEntity>> OBSERVER = ENTITIES.register("soul_observer",
            () -> EntityType.Builder.<ObserverEntity>of(ObserverEntity::new, EntityClassification.MISC)
                    .sized(0.4F, 0.4F).noSave().fireImmune().clientTrackingRange(8).updateInterval(20)
                    .setCustomClientFactory((spawn, world) -> new ObserverEntity(ModEntities.OBSERVER.get(), world))
                    .build(EcMoons.MOD_ID + ":soul_observer"));

    /** Thread Moon: Knotbind's yarn projectile. */
    public static final RegistryObject<EntityType<net.ecmoons.feature.thread.YarnEntity>> YARN = ENTITIES.register("knot_yarn",
            () -> EntityType.Builder.<net.ecmoons.feature.thread.YarnEntity>of(net.ecmoons.feature.thread.YarnEntity::new, EntityClassification.MISC)
                    .sized(0.25F, 0.25F).noSave().fireImmune().clientTrackingRange(4).updateInterval(1)
                    .setCustomClientFactory((spawn, world) -> new net.ecmoons.feature.thread.YarnEntity(ModEntities.YARN.get(), world))
                    .build(EcMoons.MOD_ID + ":knot_yarn"));

    private ModEntities() {}
}
