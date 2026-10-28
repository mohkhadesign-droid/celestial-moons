package net.ecmoons.feature;

import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Matchers;
import net.ecmoons.config.Settings;
import net.minecraft.block.BlockState;
import net.minecraft.state.IntegerProperty;
import net.minecraft.state.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Random;

/**
 * Harvest Moon additions. Enhanced Celestials' doubled crop drops are kept as the ONLY drop multiplier
 * (no second drop hook is added), with one fix: drops from breaking an immature crop are not doubled.
 */
public final class HarvestFeatures {
    /** Block state being broken by Block.dropResources on this thread (set by BlockMixin). */
    private static final ThreadLocal<BlockState> DROPPING = new ThreadLocal<>();
    /** Re-entrancy guard for the extra random tick. */
    private static boolean extraTicking;
    private static long extraTickCount;

    private HarvestFeatures() {}

    public static void beginDrops(BlockState state) {
        DROPPING.set(state);
    }

    public static void endDrops() {
        DROPPING.remove();
    }

    /** Called from HarvestMoonMixin before Enhanced Celestials multiplies a crop drop. */
    public static boolean allowDropMultiplier() {
        if (!Settings.server().b("harvest.requireMatureCrops")) return true;
        BlockState state = DROPPING.get();
        return state == null || isMature(state); // null: drops not from a block break (e.g. right-click harvest)
    }

    /** A block with an integer "age" property is mature only at its highest age; other blocks count as mature. */
    public static boolean isMature(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty && "age".equals(property.getName())) {
                IntegerProperty age = (IntegerProperty) property;
                return state.getValue(age) >= Collections.max(age.getPossibleValues());
            }
        }
        return true;
    }

    /** Called from AbstractBlockStateMixin after a random tick: run extra ticks for configured crops only. */
    public static void afterRandomTick(ServerWorld world, BlockPos pos, Random random) {
        if (extraTicking) return;
        if (!Moons.is(world, MoonType.HARVEST)) return;
        BlockState state = world.getBlockState(pos);
        Settings s = Settings.server();
        if (!Matchers.block(s, "harvest.growthBlocks", state.getBlock())) return;
        double extra = s.d("harvest.growthMultiplier") - 1.0;
        extraTicking = true;
        try {
            while (extra > 0) {
                if (extra >= 1.0 || random.nextDouble() < extra) {
                    BlockState current = world.getBlockState(pos);
                    if (!Matchers.block(s, "harvest.growthBlocks", current.getBlock()) || !current.isRandomlyTicking()) break;
                    current.randomTick(world, pos, random);
                    extraTickCount++;
                }
                extra -= 1.0;
            }
        } finally {
            extraTicking = false;
        }
    }

    /** Number of extra crop random ticks performed (diagnostics / self test). */
    public static long extraTicks() {
        return extraTickCount;
    }

    public static float exhaustion(@Nullable net.minecraft.entity.player.PlayerEntity player, float amount) {
        if (player == null || player.level.isClientSide || !Moons.is(player.level, MoonType.HARVEST)) return amount;
        return amount * (float) Settings.server().d("harvest.exhaustionMultiplier");
    }
}
