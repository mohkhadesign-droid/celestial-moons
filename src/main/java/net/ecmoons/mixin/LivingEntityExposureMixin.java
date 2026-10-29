package net.ecmoons.mixin;

import net.ecmoons.feature.reveal.RevealState;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * True Exposure (Revealing Moon). Vanilla recomputes the synchronised invisible flag from the Invisibility effect
 * whenever effects change (and the server player also when its abilities change). Right after that, a revealed
 * player's potion invisibility is held off again, so the client renders them and server-side detection
 * (getVisibilityPercent) sees them, while the effect itself keeps running untouched. Server side only.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityExposureMixin {
    @Inject(method = "updateInvisibilityStatus", at = @At("RETURN"))
    private void ecmoons$trueExposure(CallbackInfo ci) {
        RevealState.afterInvisibilityUpdate((LivingEntity) (Object) this);
    }
}
