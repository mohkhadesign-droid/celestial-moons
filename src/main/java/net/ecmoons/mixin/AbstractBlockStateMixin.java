package net.ecmoons.mixin;

import net.ecmoons.feature.HarvestFeatures;
import net.minecraft.block.AbstractBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * Harvest Moon growth: after a block's normal random tick, configured crops get extra random ticks.
 * Only configured crop blocks are affected; the world's random tick speed is never changed.
 */
@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class AbstractBlockStateMixin {
    @Inject(method = "randomTick", at = @At("TAIL"))
    private void ecmoons$extraCropTicks(ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        HarvestFeatures.afterRandomTick(world, pos, random);
    }
}
