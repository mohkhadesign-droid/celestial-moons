package net.ecmoons.mixin.client;

import net.ecmoons.client.atmo.Atmosphere;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Atmosphere colour grading through the light map. After vanilla and Enhanced Celestials have computed all 16x16
 * light-map pixels, and right before the texture is uploaded, each pixel is tinted in proportion to its sky-light
 * level only: caves, interiors and torch light keep their normal colour, block textures keep their own colours,
 * and nothing in the world changes. This injects at a single call without touching the pixel loop, so Enhanced
 * Celestials' own local-capturing injection in the same method is unaffected. Optional (require = 0).
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
    @Shadow @Final private NativeImage lightPixels;

    @Inject(method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;upload()V"),
            require = 0)
    private void ecmoons$grade(float partialTicks, CallbackInfo ci) {
        if (!Atmosphere.gradingNeeded()) return;
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                lightPixels.setPixelRGBA(block, sky, Atmosphere.gradeLightmap(block, sky, lightPixels.getPixelRGBA(block, sky)));
            }
        }
    }
}
