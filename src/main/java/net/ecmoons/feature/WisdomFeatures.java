package net.ecmoons.feature;

import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Matchers;
import net.ecmoons.config.Settings;
import net.minecraft.entity.EntityClassification;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Wisdom Moon: doubles XP at the two points where it is created, never at pickup:
 * <ul>
 *   <li>LivingExperienceDropEvent (fired once when a mob's XP is computed on death) for naturally spawned
 *       hostile monsters killed by a real player;</li>
 *   <li>BlockEvent.BreakEvent's XP value (computed once per break, already 0 with Silk Touch) for manually
 *       mined, natural, configured XP ores.</li>
 * </ul>
 * XP bottles, smelting, breeding, fishing, trading and spawner mobs are untouched.
 */
public final class WisdomFeatures {
    private WisdomFeatures() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onMobXp(LivingExperienceDropEvent event) {
        LivingEntity entity = event.getEntityLiving();
        World world = entity.level;
        if (world.isClientSide || !Moons.is(world, MoonType.WISDOM)) return;
        PlayerEntity player = event.getAttackingPlayer();
        if (!ProspectorFeatures.isManualMiner(player)) return; // real, non-fake server player
        if (!isHostile(entity)) return;
        Settings s = Settings.server();
        if (!SpawnOrigins.eligible(entity, s.list("wisdom.eligibleSpawnOrigins"), s.b("wisdom.untrackedMobsEligible"))) return;
        int xp = event.getDroppedExperience();
        if (xp > 0) event.setDroppedExperience((int) Math.round(xp * s.d("wisdom.mobXpMultiplier")));
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onOreXp(BlockEvent.BreakEvent event) {
        if (!(event.getWorld() instanceof World)) return;
        World world = (World) event.getWorld();
        if (world.isClientSide || !Moons.is(world, MoonType.WISDOM)) return;
        if (event.getExpToDrop() <= 0 || !ProspectorFeatures.isManualMiner(event.getPlayer())) return;
        Settings s = Settings.server();
        if (!Matchers.block(s, "wisdom.xpOreBlocks", event.getState().getBlock())) return;
        if (!PlacedBlocks.isNatural(world, event.getPos())) return;
        event.setExpToDrop((int) Math.round(event.getExpToDrop() * s.d("wisdom.oreXpMultiplier")));
    }

    public static boolean isHostile(LivingEntity entity) {
        return entity instanceof IMob || entity.getType().getCategory() == EntityClassification.MONSTER;
    }
}
