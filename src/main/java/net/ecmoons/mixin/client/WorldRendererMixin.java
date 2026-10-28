package net.ecmoons.mixin.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.ecmoons.client.MoonSkyRenderer;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moon designs. Both injections are optional (require = 0): if another mod rewrites the sky renderer the game
 * still starts and the moon simply falls back to Enhanced Celestials' appearance.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    @Shadow private ClientWorld level;
    @Shadow @Final private TextureManager textureManager;

    /** After Enhanced Celestials bound and tinted the moon (it injects just before getMoonPhase). */
    @Inject(method = "renderSky(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;getMoonPhase()I", shift = At.Shift.AFTER),
            require = 0)
    private void ecmoons$bindMoonDesign(MatrixStack matrixStack, float partialTicks, CallbackInfo ci) {
        MoonSkyRenderer.bindMoon(textureManager, level, partialTicks);
    }

    /** The moon has been drawn; the celestial rotation is still on the stack until the stars are done. */
    @Inject(method = "renderSky(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;getStarBrightness(F)F"),
            require = 0)
    private void ecmoons$moonDetails(MatrixStack matrixStack, float partialTicks, CallbackInfo ci) {
        MoonSkyRenderer.renderDetails(matrixStack, textureManager, level, partialTicks);
        net.ecmoons.client.atmo.AtmoSky.renderCelestial(matrixStack, level, partialTicks, MoonSkyRenderer.moonSize(level));
    }

    /** Atmosphere sky layers fixed to the horizon: after the sky dome, before sun, moon and stars. */
    @Inject(method = "renderSky(Lcom/mojang/blaze3d/matrix/MatrixStack;F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/DimensionRenderInfo;getSunriseColor(FF)[F"),
            require = 0)
    private void ecmoons$atmosphereSky(MatrixStack matrixStack, float partialTicks, CallbackInfo ci) {
        net.ecmoons.client.atmo.AtmoSky.renderFixed(matrixStack, level, partialTicks);
    }
}
