package net.ecmoons.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.ecmoons.EcMoons;
import net.ecmoons.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.util.ResourceLocation;

/**
 * Revealing Moon colour loss while YOU are revealed, for the world only.
 * <p>
 * DESATURATE (default): after the world and your hand are drawn and before the HUD (the same point where vanilla
 * applies its own spectator post effects), the frame goes through vanilla's color_convolve program with a lowered
 * saturation and a slight pale-gold colour scale, scaled by your reveal intensity. The HUD, chat and menus are drawn
 * afterwards and are never recoloured. The Revealing Moon's own persistent light tint is untouched (it is in the
 * light map) - this pass is applied on top of the current frame, so nothing old is ever restored.
 * <p>
 * WASH: no post-processing; a restrained pale-gold, lower-saturation light-map wash instead (Atmosphere). Used when
 * chosen, or automatically if the post pass cannot be loaded (e.g. an incompatible shader setup).
 */
public final class RevealGrade {
    private static final ResourceLocation CHAIN = new ResourceLocation(EcMoons.MOD_ID, "shaders/post/reveal_seen.json");
    private static ShaderGroup group;
    private static Shader convolve;
    private static boolean failed;
    private static int width = -1, height = -1;
    /** Frames the colour pass was applied (client test). */
    public static volatile int applied;
    /** Last saturation sent to the pass (client test / diagnostics). */
    public static volatile float lastSaturation = 1f;

    private RevealGrade() {}

    private static float strength(float partialTicks) {
        return RevealClient.revealIntensity(partialTicks) * ClientConfig.REVEAL_COLOR.get().floatValue();
    }

    public static boolean usingWash() {
        return ClientConfig.REVEAL_COLOR_MODE.get() == ClientConfig.ColorMode.WASH || failed;
    }

    /** Light-map wash amount for the WASH mode (0 when the post pass is used). */
    public static float washAmount(float partialTicks) {
        if (!usingWash()) return 0f;
        return 0.55f * strength(partialTicks);
    }

    /** Called from GameRendererMixin after the level (and hand) are rendered, before the HUD. */
    public static void afterWorld(float partialTicks) {
        if (usingWash()) return;
        float k = strength(partialTicks);
        if (k <= 0.003f) return;
        Minecraft mc = Minecraft.getInstance();
        if (!ensure(mc)) return;
        float sat = 1f - 0.6f * k; // restrained: at most 60% of the colour is lost
        lastSaturation = sat;
        convolve.getEffect().safeGetUniform("Saturation").set(sat);
        convolve.getEffect().safeGetUniform("ColorScale").set(1f + 0.05f * k, 1f - 0.01f * k, 1f - 0.16f * k);
        convolve.getEffect().safeGetUniform("Offset").set(0.025f * k, 0.02f * k, 0.005f * k);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.disableAlphaTest();
        RenderSystem.enableTexture();
        RenderSystem.matrixMode(5890);
        RenderSystem.pushMatrix();
        RenderSystem.loadIdentity();
        group.process(partialTicks);
        RenderSystem.popMatrix();
        RenderSystem.matrixMode(5888);
        RenderSystem.enableTexture();
        RenderSystem.enableDepthTest();
        mc.getMainRenderTarget().bindWrite(true);
        applied++;
    }

    private static boolean ensure(Minecraft mc) {
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        if (group == null) {
            try {
                Framebuffer main = mc.getMainRenderTarget();
                group = new ShaderGroup(mc.getTextureManager(), mc.getResourceManager(), main, CHAIN);
                Framebuffer swap = group.getTempTarget("swap");
                convolve = group.addPass("color_convolve", main, swap);
                group.addPass("blit", swap, main);
                group.resize(w, h);
                width = w;
                height = h;
            } catch (Exception | LinkageError e) {
                EcMoons.LOGGER.warn("Revealing Moon colour pass unavailable, using the light-map wash instead: {}", e.toString());
                failed = true;
                close();
                return false;
            }
        }
        if (w != width || h != height) {
            group.resize(w, h);
            width = w;
            height = h;
        }
        return true;
    }

    public static void close() {
        if (group != null) group.close();
        group = null;
        convolve = null;
    }
}
