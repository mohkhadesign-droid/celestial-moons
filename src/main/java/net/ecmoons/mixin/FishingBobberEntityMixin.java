package net.ecmoons.mixin;

import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Settings;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blue Moon fishing: when the game picks a new wait time (time until a fish is lured, then time until it bites),
 * scale it once. Existing countdowns and Lure's own reduction are respected; nothing is applied twice.
 */
@Mixin(FishingBobberEntity.class)
public abstract class FishingBobberEntityMixin {
    @Shadow private int nibble;
    @Shadow private int timeUntilLured;
    @Shadow private int timeUntilHooked;

    @Unique private int ecmoons$prevLured;
    @Unique private int ecmoons$prevHooked;

    @Inject(method = "catchingFish", at = @At("HEAD"))
    private void ecmoons$before(BlockPos pos, CallbackInfo ci) {
        ecmoons$prevLured = timeUntilLured;
        ecmoons$prevHooked = timeUntilHooked;
    }

    @Inject(method = "catchingFish", at = @At("TAIL"))
    private void ecmoons$after(BlockPos pos, CallbackInfo ci) {
        FishingBobberEntity self = (FishingBobberEntity) (Object) this;
        if (self.level.isClientSide || !Moons.is(self.level, MoonType.BLUE)) return;
        double m = Settings.server().d("blue.fishingWaitMultiplier");
        if (ecmoons$prevLured <= 0 && ecmoons$prevHooked <= 0 && timeUntilLured > 0) {
            timeUntilLured = Math.max(1, (int) Math.round(timeUntilLured * m));
        }
        if (ecmoons$prevHooked <= 0 && timeUntilHooked > 0) {
            timeUntilHooked = Math.max(1, (int) Math.round(timeUntilHooked * m));
        }
    }
}
