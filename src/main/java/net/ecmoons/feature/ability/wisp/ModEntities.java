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

    private ModEntities() {}
}
