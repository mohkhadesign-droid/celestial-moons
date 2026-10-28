package net.ecmoons.feature;

import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Settings;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Drift Moon server side: fall damage reduction and cheaper jumps. Jump height and slower falling are applied
 * where player movement is actually simulated - on the player's own client (client.DriftClient) - so the
 * server never fights the client's movement. Projectiles, sprint and attack speed are untouched.
 * <p>
 * Safe transition: a player who is airborne when the moon ends keeps the fall-damage reduction until landing.
 */
public final class DriftFeatures {
    private static final Set<UUID> AIRBORNE_GRACE = new HashSet<>();

    private DriftFeatures() {}

    /** Called every tick per player on the server. */
    public static void tick(ServerPlayerEntity player, MoonType active) {
        if (active == MoonType.DRIFT) {
            if (!player.isOnGround()) AIRBORNE_GRACE.add(player.getUUID());
            else AIRBORNE_GRACE.remove(player.getUUID());
        } else if (player.isOnGround() || player.isInWater()) {
            AIRBORNE_GRACE.remove(player.getUUID());
        }
    }

    public static boolean hasGrace(PlayerEntity player) {
        return AIRBORNE_GRACE.contains(player.getUUID());
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onFall(LivingFallEvent event) {
        LivingEntity entity = event.getEntityLiving();
        if (entity.level.isClientSide || !(entity instanceof PlayerEntity)) return;
        PlayerEntity player = (PlayerEntity) entity;
        if (Moons.is(player.level, MoonType.DRIFT) || hasGrace(player)) {
            event.setDamageMultiplier(event.getDamageMultiplier() * (float) Settings.server().d("drift.fallDamageMultiplier"));
        }
        AIRBORNE_GRACE.remove(player.getUUID());
    }

    /** Jump exhaustion multiplier (called from PlayerEntityMixin for the two jump exhaustion calls). */
    public static float jumpExhaustion(PlayerEntity player, float amount) {
        if (player.level.isClientSide || !Moons.is(player.level, MoonType.DRIFT)) return amount;
        return amount * (float) Settings.server().d("drift.jumpExhaustionMultiplier");
    }

    public static void forget(UUID player) {
        AIRBORNE_GRACE.remove(player);
    }
}
