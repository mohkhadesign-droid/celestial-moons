package net.ecmoons.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import corgitaco.enhancedcelestials.LunarContext;
import corgitaco.enhancedcelestials.api.lunarevent.LunarEvent;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.ClientConfig;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraft.util.math.vector.Matrix4f;

/**
 * Draws the ten moon designs inside vanilla's sky renderer (called from WorldRendererMixin).
 * <p>
 * Static: every moon's surface, craters, phases (4x2 sheet, identical layout to vanilla moon_phases.png) and halo.
 * Animated (optional, client config): a second additive "detail" layer drawn on the same quad -
 * pulsing glows (Blood fissures, Blue/Harvest/Prospector glints, Frost cracks, Arcane runes, Wisdom
 * constellations), mist wisps drifting sideways (Mist) and floating fragments bobbing (Drift). Quiet has none.
 */
public final class MoonSkyRenderer {
    private MoonSkyRenderer() {}

    static MoonType visibleMoon(ClientWorld level) {
        if (level == null || !ClientConfig.USE_ADDON_MOON_TEXTURES.get()) return MoonType.NONE;
        if (net.ecmoons.client.atmo.Atmosphere.preview() != null) return net.ecmoons.client.atmo.Atmosphere.preview();
        LunarContext ctx = Moons.context(level);
        if (ctx == null) return MoonType.NONE;
        return MoonType.of(ctx.getCurrentEvent());
    }

    /** Right after vanilla/Enhanced Celestials bound the moon texture: bind ours and draw it untinted. */
    public static void bindMoon(TextureManager textures, ClientWorld level, float partialTicks) {
        MoonType type = visibleMoon(level);
        if (type == MoonType.NONE) return;
        textures.bind(type == MoonType.REVEALING ? RevealClient.eyeTexture(partialTicks) : type.moonTexture());
        // Enhanced Celestials tints the moon with the event colour; our designs carry their own colours.
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, 1.0F - level.getRainLevel(partialTicks));
    }

    /** Moon size used by Enhanced Celestials for the current event (Super variants are bigger). */
    public static float moonSize(ClientWorld level) {
        LunarEvent event = Moons.currentEvent(level);
        return event != null && event.getClient() != null ? event.getClient().getMoonSize() : 20.0F;
    }

    /** Just before stars are drawn (moon already drawn, same celestial rotation): draw the detail layer. */
    public static void renderDetails(MatrixStack matrixStack, TextureManager textures, ClientWorld level, float partialTicks) {
        if (!ClientConfig.RENDER_MOON_DETAILS.get()) return;
        MoonType type = visibleMoon(level);
        ResourceLocation details = type.detailTexture();
        if (details == null) return;
        if (type == MoonType.REVEALING && RevealClient.frame(partialTicks) != 0) return; // veins only on the open eye
        LunarEvent event = Moons.currentEvent(level);
        float size = event != null && event.getClient() != null ? event.getClient().getMoonSize() : 20.0F;
        float rain = 1.0F - level.getRainLevel(partialTicks);
        boolean animate = ClientConfig.ANIMATE_MOON_DETAILS.get();
        double t = Util.getMillis() / 1000.0;

        float alpha;
        float dx = 0, dz = 0;
        switch (type) {
            case MIST:
                alpha = 0.9F;
                if (animate) dx = (float) (Math.sin(t * 0.35) * size * 0.07);
                break;
            case DRIFT:
                alpha = 1.0F;
                if (animate) dz = (float) (Math.sin(t * 0.9) * size * 0.035);
                break;
            case AFTERIMAGE: // the fading duplicates trail behind the moon, drifting very slightly
                alpha = 0.5F;
                dx = size * 0.13F + (animate ? (float) Math.sin(t * 0.5) * size * 0.015F : 0F);
                dz = -size * 0.06F;
                break;
            case SHELL: // the mint central plate breathes slowly
                alpha = animate ? (float) (0.45 + 0.35 * Math.sin(t * 0.6)) : 0.6F;
                break;
            case GLUTTONY: // sesame seeds: part of the bun, no animation
                alpha = 1.0F;
                break;
            case REVEALING:
                alpha = animate ? (float) (0.55 + 0.45 * Math.sin(t * 1.3)) : 0.6F;
                alpha = Math.max(alpha, RevealClient.irisBoost(partialTicks)); // the iris brightens before a scan
                break;
            case BLOOD:
            case WAR:
                alpha = animate ? (float) (0.55 + 0.45 * Math.sin(t * 1.3)) : 0.6F;
                break;
            case ARCANE:
                alpha = animate ? (float) (0.5 + 0.5 * Math.sin(t * 0.9)) : 0.6F;
                break;
            default: // glints and constellations twinkle a little faster
                alpha = animate ? (float) (0.5 + 0.5 * Math.sin(t * 2.1 + type.ordinal())) : 0.6F;
                break;
        }
        alpha *= rain;
        if (alpha <= 0.01F) return;

        int phase = level.getMoonPhase();
        int col = phase % 4;
        int row = phase / 4 % 2;
        float u0 = col / 4.0F, v0 = row / 2.0F, u1 = (col + 1) / 4.0F, v1 = (row + 1) / 2.0F;

        RenderSystem.enableTexture();
        textures.bind(details);
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, alpha);
        Matrix4f m = matrixStack.last().pose();
        int copies = type == MoonType.AFTERIMAGE ? 2 : 1;
        for (int k = 1; k <= copies; k++) {
            if (k > 1) RenderSystem.color4f(1.0F, 1.0F, 1.0F, alpha * 0.45F);
            float ox = dx * k, oz = dz * k;
            BufferBuilder buffer = Tessellator.getInstance().getBuilder();
            buffer.begin(7, DefaultVertexFormats.POSITION_TEX);
            buffer.vertex(m, -size + ox, -100.0F, size + oz).uv(u1, v1).endVertex();
            buffer.vertex(m, size + ox, -100.0F, size + oz).uv(u0, v1).endVertex();
            buffer.vertex(m, size + ox, -100.0F, -size + oz).uv(u0, v0).endVertex();
            buffer.vertex(m, -size + ox, -100.0F, -size + oz).uv(u1, v0).endVertex();
            buffer.end();
            WorldVertexBufferUploader.end(buffer);
        }
        RenderSystem.color4f(1.0F, 1.0F, 1.0F, rain);
        RenderSystem.disableTexture();
    }
}
