package net.ecmoons.feature;

import net.minecraft.entity.Entity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Records how each mob came into the world, in the mob's persistent NBT (so it survives saving and reloading).
 * <ul>
 *   <li>Forge's CheckSpawn event marks mobs from mob spawners as SPAWNER (including spawners with custom NBT).</li>
 *   <li>A mixin at the start of MobEntity.finalizeSpawn records the vanilla SpawnReason for every other path
 *       (NATURAL, CHUNK_GENERATION, SPAWN_EGG, COMMAND, BREEDING, CONVERSION, PATROL, EVENT, ...).</li>
 * </ul>
 * The first recorded origin wins, so a spawner mob stays SPAWNER even though finalizeSpawn also runs for it.
 * Mobs that never pass through either hook (spawned before installation, or by a mod that skips both) have no
 * origin and are treated by each moon's "untrackedMobsEligible" setting.
 */
public final class SpawnOrigins {
    public static final String TAG = "ecmoons_origin";

    private SpawnOrigins() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCheckSpawn(LivingSpawnEvent.CheckSpawn event) {
        if (event.getWorld().isClientSide()) return;
        if (event.isSpawner()) {
            record(event.getEntity(), SpawnReason.SPAWNER);
        } else if (event.getSpawnReason() != null) {
            record(event.getEntity(), event.getSpawnReason());
        }
    }

    /** Called from MobEntityMixin at the head of finalizeSpawn. */
    public static void onFinalizeSpawn(MobEntity mob, SpawnReason reason) {
        if (!mob.level.isClientSide && reason != null) record(mob, reason);
    }

    public static void record(Entity entity, SpawnReason reason) {
        if (entity == null) return;
        if (!entity.getPersistentData().contains(TAG)) {
            entity.getPersistentData().putString(TAG, reason.name());
        }
    }

    @Nullable
    public static String origin(Entity entity) {
        return entity.getPersistentData().contains(TAG) ? entity.getPersistentData().getString(TAG) : null;
    }

    public static boolean eligible(Entity entity, List<String> eligibleOrigins, boolean untrackedEligible) {
        String origin = origin(entity);
        if (origin == null) return untrackedEligible;
        return eligibleOrigins.contains(origin);
    }
}
