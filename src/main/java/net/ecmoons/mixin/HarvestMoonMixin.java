package net.ecmoons.mixin;

import corgitaco.enhancedcelestials.lunarevent.HarvestMoon;
import net.ecmoons.feature.HarvestFeatures;
import net.minecraft.item.ItemStack;
import net.minecraft.world.server.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enhanced Celestials' Harvest Moon doubles any crop item dropped by a block. Skip that when the broken block
 * was an immature crop (otherwise planting and immediately breaking an age-0 carrot duplicates carrots).
 * This is the only crop-drop multiplier in play; the add-on never adds a second one.
 */
@Mixin(value = HarvestMoon.class, remap = false)
public abstract class HarvestMoonMixin {
    @Inject(method = "onBlockItemDrop", at = @At("HEAD"), cancellable = true)
    private void ecmoons$onlyMatureCrops(ServerWorld world, ItemStack itemStack, CallbackInfo ci) {
        if (!HarvestFeatures.allowDropMultiplier()) ci.cancel();
    }
}
