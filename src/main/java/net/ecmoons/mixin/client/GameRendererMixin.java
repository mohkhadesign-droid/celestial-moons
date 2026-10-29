package net.ecmoons.mixin.client;

import net.ecmoons.client.RevealGrade;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Revealing Moon colour loss: runs right after the level (and the hand) were drawn and before vanilla's own post
 * effect and the HUD, so only the world is recoloured. Optional (require = 0): without it the colour loss is simply
 * absent (the light-map WASH mode does not need it).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render(FJZ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/WorldRenderer;doEntityOutline()V", shift = At.Shift.AFTER),
            require = 0)
    private void ecmoons$revealColour(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        RevealGrade.afterWorld(partialTicks);
    }
}
