package net.ecmoons.mixin.client;

import net.ecmoons.client.atmo.Atmosphere;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Atmosphere colour grading through the light map: each finished pixel (block light x sky light) is tinted in
 * proportion to its sky-light level only, so caves, interiors and torch light keep their normal colour, block
 * textures keep their own colours, and nothing is changed in the world. Runs after Enhanced Celestials' own
 * sky-light colouring (which it extends rather than replaces). Optional: if another mod rewrites this method,
 * the injection is skipped and only the grading is lost.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
    @ModifyArg(method = "updateLightTexture",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/NativeImage;setPixelRGBA(III)V"),
            index = 2, require = 0)
    private int ecmoons$grade(int x, int y, int color) {
        return Atmosphere.gradeLightmap(x, y, color);
    }
}
