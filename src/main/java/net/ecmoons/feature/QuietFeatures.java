package net.ecmoons.feature;

import net.ecmoons.MoonType;
import net.ecmoons.config.Settings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Quiet Moon recovery: extra natural regeneration for well-fed players who have been out of combat for a while.
 * The combat timer resets whenever a player deals or receives damage that comes from an entity (melee,
 * projectiles, entity explosions). Falls, starvation, fire and similar are not combat. The hostile cap
 * reduction lives in AddonMoonEvent (Enhanced Celestials' spawn-cap hook). PvP, claims and raids are untouched.
 */
public final class QuietFeatures {
    private static final Map<UUID, Long> LAST_COMBAT = new HashMap<>();

    private QuietFeatures() {}

    @SubscribeEvent(priority = EventPriority.MONITOR, receiveCanceled = false)
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntityLiving();
        if (victim.level.isClientSide) return;
        Entity attacker = event.getSource().getEntity();
        if (attacker == null) return;
        long now = victim.level.getGameTime();
        if (victim instanceof PlayerEntity) LAST_COMBAT.put(victim.getUUID(), now);
        if (attacker instanceof PlayerEntity && attacker != victim) LAST_COMBAT.put(attacker.getUUID(), now);
    }

    public static long lastCombat(UUID player) {
        Long t = LAST_COMBAT.get(player);
        return t == null ? Long.MIN_VALUE / 2 : t;
    }

    /** Called every tick per player on the server. */
    public static void tick(ServerPlayerEntity player, MoonType active) {
        if (active != MoonType.QUIET) return;
        Settings s = Settings.server();
        int interval = Math.max(1, s.i("quiet.regenIntervalTicks"));
        if (player.tickCount % interval != 0) return;
        if (!player.isAlive() || player.getHealth() >= player.getMaxHealth()) return;
        if (player.getFoodData().getFoodLevel() < s.i("quiet.minFoodLevel")) return;
        long since = player.level.getGameTime() - lastCombat(player.getUUID());
        if (since < 20L * s.i("quiet.combatCooldownSeconds")) return;
        player.heal((float) s.d("quiet.regenAmount"));
    }

    public static void forget(UUID player) {
        LAST_COMBAT.remove(player);
    }
}
