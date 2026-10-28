package net.ecmoons.client.atmo;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.client.RevealClient;
import net.ecmoons.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraft.util.math.vector.Matrix4f;

import java.util.Random;

/**
 * Sky layers drawn inside the vanilla sky pass (from WorldRendererMixin), so terrain, clouds and other mods' sky
 * objects still draw over them and nothing in the sky renderer is replaced:
 * <ul>
 *   <li>fixed to the horizon (after the sky dome, before sun and moon): Arcane ribbons, Drift cloud streaks,
 *       Blood wisps, War smoke bands with orange flashes, Thread strands;</li>
 *   <li>turning with the stars (right after the moon): moon halos (Frost shimmer, Revealing warning halo, Arcane
 *       aura...), the Revealing scan ring, brighter stars (Blue, Wisdom) and Wisdom's constellations.</li>
 * </ul>
 * All geometry is computed from fixed seeds and the time; nothing is allocated per frame except the vertex data.
 */
public final class AtmoSky {
    private static final ResourceLocation HALO = new ResourceLocation(EcMoons.MOD_ID, "textures/atmosphere/halo.png");
    private static final int STARS = 420;
    private static final float[] starX = new float[STARS], starY = new float[STARS], starZ = new float[STARS], starS = new float[STARS], starP = new float[STARS];
    private static final int CONST = 6, CONST_STARS = 6;
    private static final float[][] constX = new float[CONST][CONST_STARS], constY = new float[CONST][CONST_STARS], constZ = new float[CONST][CONST_STARS];
    public static int lastLayers; // diagnostics: layers drawn in the last frame

    static {
        Random r = new Random(1984);
        for (int i = 0; i < STARS; i++) {
            double u = r.nextDouble() * 2 - 1, th = r.nextDouble() * Math.PI * 2, s = Math.sqrt(1 - u * u);
            starX[i] = (float) (s * Math.cos(th));
            starY[i] = (float) u;
            starZ[i] = (float) (s * Math.sin(th));
            starS[i] = 0.18f + r.nextFloat() * r.nextFloat() * 0.45f;
            starP[i] = r.nextFloat() * 6.28f;
        }
        for (int c = 0; c < CONST; c++) {
            double az = c * Math.PI * 2 / CONST + r.nextDouble() * 0.4, el = -0.15 + r.nextDouble() * 0.9; // in the star frame
            double cx = Math.cos(el) * Math.cos(az), cy = Math.sin(el), cz = Math.cos(el) * Math.sin(az);
            double px = cx, py = cy, pz = cz;
            for (int k = 0; k < CONST_STARS; k++) {
                constX[c][k] = (float) px;
                constY[c][k] = (float) py;
                constZ[c][k] = (float) pz;
                // next star: a step of ~6-10 degrees in a wandering direction
                double ang = r.nextDouble() * Math.PI * 2, step = 0.09 + r.nextDouble() * 0.08;
                double ux = -Math.sin(az), uy = 0, uz = Math.cos(az); // tangent basis
                double vx = -Math.sin(el) * Math.cos(az), vy = Math.cos(el), vz = -Math.sin(el) * Math.sin(az);
                px += (ux * Math.cos(ang) + vx * Math.sin(ang)) * step;
                py += (uy * Math.cos(ang) + vy * Math.sin(ang)) * step;
                pz += (uz * Math.cos(ang) + vz * Math.sin(ang)) * step;
                double len = Math.sqrt(px * px + py * py + pz * pz);
                px /= len; py /= len; pz /= len;
            }
        }
    }

    private AtmoSky() {}

    private static boolean enabled() {
        return Atmosphere.on("enableSkyAnimations");
    }

    private static float time(float pt) {
        return (Atmosphere.ticks() + pt) / 20f;
    }

    private static int segments() {
        return Atmosphere.quality() == ClientConfig.Quality.HIGH ? 64 : Atmosphere.quality() == ClientConfig.Quality.LOW ? 20 : 40;
    }

    /** Point on the sky sphere (radius 100) from azimuth/elevation in radians. */
    private static void dir(float az, float el, float[] out) {
        float ce = (float) Math.cos(el);
        out[0] = (float) (ce * Math.sin(az)) * 100f;
        out[1] = (float) Math.sin(el) * 100f;
        out[2] = (float) (ce * Math.cos(az)) * 100f;
    }

    // ---- horizon-fixed layers -------------------------------------------------------------------------------------

    /** Called right after the sky dome is drawn (camera rotation only on the stack). */
    public static void renderFixed(MatrixStack ms, ClientWorld level, float pt) {
        lastLayers = 0;
        if (!enabled() || !Atmosphere.anyActive()) return;
        float night = Atmosphere.night(level, pt) * (1f - level.getRainLevel(pt) * 0.5f);
        if (night <= 0.02f) return;
        Matrix4f m = ms.last().pose();
        float t = time(pt);
        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.disableAlphaTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableFog();
        RenderSystem.shadeModel(7425);

        float k;
        if ((k = layer(MoonType.ARCANE, pt) * night) > 0) {
            AtmoProfile p = AtmoProfile.of(MoonType.ARCANE);
            additive();
            for (int i = 0; i < 3; i++) {
                float base = (float) Math.toRadians(32 + 14 * i), az0 = (float) Math.toRadians(t * 1.6f * p.animation + i * 120);
                float shimmer = 0.55f + 0.45f * (float) Math.sin(t * (0.37 + 0.11 * i) + i * 2.1) * (float) Math.sin(t * 0.13 + i);
                ribbon(m, az0, (float) Math.toRadians(130), base, (float) Math.toRadians(9), t * (0.21f + 0.07f * i) * p.animation, 3.2f + i,
                        i % 2 == 0 ? p.glow : p.accent, p.horizon, 0.32f * k * shimmer, i * 7.3f);
            }
            lastLayers++;
        }
        if ((k = layer(MoonType.DRIFT, pt) * night) > 0) {
            AtmoProfile p = AtmoProfile.of(MoonType.DRIFT);
            additive();
            for (int i = 0; i < 6; i++) {
                float az0 = (float) Math.toRadians(i * 60 + t * 1.2f * p.animation), el = (float) Math.toRadians(52 + (i * 37) % 24);
                ribbon(m, az0, (float) Math.toRadians(70), el, (float) Math.toRadians(1.5), t * 0.05f, 1.1f, p.accent, p.glow, 0.2f * k, i * 3.1f);
            }
            lastLayers++;
        }
        if ((k = layer(MoonType.BLOOD, pt) * night) > 0) {
            AtmoProfile p = AtmoProfile.of(MoonType.BLOOD);
            additive();
            for (int i = 0; i < 3; i++) {
                float az0 = (float) Math.toRadians(i * 120 + t * 0.6f * p.animation);
                ribbon(m, az0, (float) Math.toRadians(80), (float) Math.toRadians(12 + 6 * i), (float) Math.toRadians(3), t * 0.04f, 7f,
                        p.accent, p.horizon, 0.09f * k, i * 5f);
            }
            lastLayers++;
        }
        if ((k = layer(MoonType.WAR, pt) * night) > 0) {
            AtmoProfile p = AtmoProfile.of(MoonType.WAR);
            float turb = AtmoWar.turbulence();
            // distant orange flashes behind the smoke (deterministic per time slot, more frequent as battle nears)
            additive();
            int interval = AtmoWar.flashInterval();
            long slot = (Atmosphere.ticks()) / Math.max(20, interval);
            float within = ((Atmosphere.ticks() % Math.max(20, interval)) + pt) / 12f;
            Random r = new Random(slot * 31 + 7);
            if (within < 1f || AtmoWar.summonFlash() > 0) {
                float fa = Math.max(within < 1f ? (1f - within) : 0f, AtmoWar.summonFlash());
                float az = r.nextFloat() * 6.28f, el = (float) Math.toRadians(8 + r.nextFloat() * 18);
                glowSpot(m, az, el, 34f + 20 * AtmoWar.summonFlash(), p.accent, 0.5f * k * fa);
            }
            // smoke bands
            RenderSystem.defaultBlendFunc();
            for (int i = 0; i < 4; i++) {
                float az0 = (float) Math.toRadians(i * 90 + t * (0.9f + 3f * turb) * p.animation);
                float[] smoke = {0.1f, 0.1f, 0.115f};
                ribbon(m, az0, (float) Math.toRadians(110), (float) Math.toRadians(9 + 7 * i), (float) Math.toRadians(4 + 3 * turb),
                        t * (0.05f + 0.1f * turb), 12f, smoke, p.sky, (0.42f + 0.25f * turb) * k, i * 4f);
            }
            lastLayers++;
        }
        if ((k = layer(MoonType.THREAD, pt) * night) > 0 && AtmoProfile.of(MoonType.THREAD).flag("skyStrands")) {
            AtmoProfile p = AtmoProfile.of(MoonType.THREAD);
            additive();
            for (int i = 0; i < 5; i++) {
                float period = 26f + i * 3f;
                float cyc = (t + i * 9f) / period;
                long seed = (long) Math.floor(cyc) * 97 + i;
                float f = cyc - (float) Math.floor(cyc);
                float fade = Math.min(1f, Math.min(f * 5f, (1f - f) * 5f));
                Random r = new Random(seed);
                float az1 = r.nextFloat() * 6.28f, az2 = az1 + 0.9f + r.nextFloat() * 1.6f;
                float el1 = (float) Math.toRadians(25 + r.nextFloat() * 50), el2 = (float) Math.toRadians(25 + r.nextFloat() * 50);
                strand(m, az1, el1, az2, el2, 0.32f, p.accent, 0.5f * k * fade * (0.7f + 0.3f * (float) Math.sin(t * 1.3 + i)));
            }
            lastLayers++;
        }
        RenderSystem.shadeModel(7424);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableTexture();
    }

    private static float layer(MoonType t, float pt) {
        float s = Atmosphere.strength(t, pt);
        return s > 0.01f && AtmoProfile.of(t).skyAnimation ? s : 0f;
    }

    private static void additive() {
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
    }

    /** A soft ribbon following azimuth az0..az0+span at a wavy elevation; alpha fades towards both ends. */
    private static void ribbon(Matrix4f m, float az0, float span, float el, float wave, float phase, float width,
                               float[] c1, float[] c2, float alpha, float seed) {
        if (alpha <= 0.003f) return;
        int n = segments();
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
        float[] p0 = new float[3], p1 = new float[3];
        for (int s = 0; s < n; s++) {
            for (int e = 0; e < 2; e++) {
                float u = (s + e) / (float) n;
                float az = az0 + span * u;
                float elv = el + wave * (float) (Math.sin(u * 6.28 * 1.3 + phase + seed) * 0.7 + Math.sin(u * 17 + phase * 2.3 + seed) * 0.3);
                float w = (float) Math.toRadians(width) * (0.6f + 0.4f * (float) Math.sin(u * 9 + phase * 1.7 + seed));
                float a = alpha * (float) Math.sin(Math.PI * u);
                float[] c = mix(c1, c2, u);
                dir(az, elv - w / 2, p0);
                dir(az, elv + w / 2, p1);
                if (e == 0) {
                    buf.vertex(m, p0[0], p0[1], p0[2]).color(c[0], c[1], c[2], a * 0.15f).endVertex();
                    buf.vertex(m, p1[0], p1[1], p1[2]).color(c[0], c[1], c[2], a).endVertex();
                } else {
                    buf.vertex(m, p1[0], p1[1], p1[2]).color(c[0], c[1], c[2], a).endVertex();
                    buf.vertex(m, p0[0], p0[1], p0[2]).color(c[0], c[1], c[2], a * 0.15f).endVertex();
                }
            }
        }
        buf.end();
        WorldVertexBufferUploader.end(buf);
    }

    /** A thin, slightly sagging strand between two sky points. */
    private static void strand(Matrix4f m, float az1, float el1, float az2, float el2, float widthDeg, float[] c, float alpha) {
        if (alpha <= 0.003f) return;
        int n = segments() / 2;
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
        float[] a = new float[3], b = new float[3];
        float w = (float) Math.toRadians(widthDeg);
        for (int s = 0; s < n; s++) {
            float u0 = s / (float) n, u1 = (s + 1) / (float) n;
            float e0 = el1 + (el2 - el1) * u0 - (float) Math.sin(Math.PI * u0) * 0.1f, e1 = el1 + (el2 - el1) * u1 - (float) Math.sin(Math.PI * u1) * 0.1f;
            float z0 = az1 + (az2 - az1) * u0, z1 = az1 + (az2 - az1) * u1;
            float a0 = alpha * (float) Math.sin(Math.PI * u0), a1 = alpha * (float) Math.sin(Math.PI * u1);
            dir(z0, e0 - w, a); dir(z0, e0 + w, b);
            buf.vertex(m, a[0], a[1], a[2]).color(c[0], c[1], c[2], a0).endVertex();
            buf.vertex(m, b[0], b[1], b[2]).color(c[0], c[1], c[2], a0).endVertex();
            dir(z1, e1 + w, b); dir(z1, e1 - w, a);
            buf.vertex(m, b[0], b[1], b[2]).color(c[0], c[1], c[2], a1).endVertex();
            buf.vertex(m, a[0], a[1], a[2]).color(c[0], c[1], c[2], a1).endVertex();
        }
        buf.end();
        WorldVertexBufferUploader.end(buf);
    }

    /** A soft round glow on the sky (triangle fan, bright centre, transparent rim). */
    private static void glowSpot(Matrix4f m, float az, float el, float sizeDeg, float[] c, float alpha) {
        if (alpha <= 0.003f) return;
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(6, DefaultVertexFormats.POSITION_COLOR);
        float[] p = new float[3];
        dir(az, el, p);
        buf.vertex(m, p[0], p[1], p[2]).color(c[0], c[1], c[2], alpha).endVertex();
        float rad = (float) Math.toRadians(sizeDeg) / 2;
        for (int i = 0; i <= 16; i++) {
            double ang = i * Math.PI * 2 / 16;
            dir(az + rad * (float) Math.cos(ang) / Math.max(0.3f, (float) Math.cos(el)), el + rad * (float) Math.sin(ang) * 0.6f, p);
            buf.vertex(m, p[0], p[1], p[2]).color(c[0], c[1], c[2], 0f).endVertex();
        }
        buf.end();
        WorldVertexBufferUploader.end(buf);
    }

    private static float[] mix(float[] a, float[] b, float u) {
        float v = 0.5f + 0.5f * (float) Math.sin(u * Math.PI * 2);
        return new float[]{a[0] + (b[0] - a[0]) * v * 0.5f, a[1] + (b[1] - a[1]) * v * 0.5f, a[2] + (b[2] - a[2]) * v * 0.5f};
    }

    // ---- star-frame layers (rotate with the moon and stars) -------------------------------------------------------

    /**
     * Called after the moon (and the add-on's detail layer) are drawn, before the stars; the celestial rotation is on
     * the stack and the blend mode is additive. Leaves texturing disabled, as vanilla expects.
     */
    public static void renderCelestial(MatrixStack ms, ClientWorld level, float pt, float moonSize) {
        if (!Atmosphere.anyActive() && RevealClient.ringProgress(pt) < 0) return;
        float rain = 1f - level.getRainLevel(pt);
        if (rain <= 0.01f) return;
        Matrix4f m = ms.last().pose();
        float t = time(pt);
        boolean anim = enabled();
        // moon halos
        Minecraft mc = Minecraft.getInstance();
        RenderSystem.enableTexture();
        mc.getTextureManager().bind(HALO);
        for (MoonType type : MoonType.MOONS) {
            float k = Atmosphere.strength(type, pt);
            if (k <= 0.01f || !anim) continue;
            AtmoProfile p = AtmoProfile.of(type);
            float a = p.moonGlow * k * 0.55f;
            if (type == MoonType.FROST) a *= p.flag("moonHalo") ? 0.75f + 0.25f * (float) Math.sin(t * 0.8) : 0f;
            if (type == MoonType.ARCANE) a *= 0.8f + 0.2f * (float) Math.sin(t * 2.3) * (float) Math.sin(t * 0.7);
            float scale = 2.4f;
            if (type == MoonType.REVEALING) {
                float h = RevealClient.haloAmount(pt);
                a = p.moonGlow * k * (0.25f + 0.75f * h);
                scale = 2.2f + 0.8f * h;
            }
            if (Atmosphere.superVariant() && type == Atmosphere.target()) a *= 1.25f;
            halo(m, moonSize * scale, p.glow, a * rain);
        }
        RenderSystem.disableTexture();
        // Revealing scan ring expanding from the moon
        float ring = RevealClient.ringProgress(pt);
        AtmoProfile rp = AtmoProfile.of(MoonType.REVEALING);
        if (ring >= 0 && anim && rp.flag("scanRing")) {
            float theta = (float) Math.toRadians(4 + 86 * ring), w = (float) Math.toRadians(1.2 + 2.0 * ring);
            ringAroundMoon(m, theta, w, rp.light, 0.55f * (1f - ring) * rain);
            lastLayers++;
        }
        // brighter stars (Blue, Wisdom) and constellations (Wisdom)
        float star = level.getStarBrightness(pt) * rain;
        float blue = Atmosphere.strength(MoonType.BLUE, pt), wis = Atmosphere.strength(MoonType.WISDOM, pt);
        if (star > 0.01f && (blue > 0.01f || wis > 0.01f)) {
            int n = Atmosphere.quality() == ClientConfig.Quality.HIGH ? STARS : Atmosphere.quality() == ClientConfig.Quality.LOW ? STARS / 3 : STARS * 2 / 3;
            float[] cb = AtmoProfile.of(MoonType.BLUE).light, cw = AtmoProfile.of(MoonType.WISDOM).accent;
            BufferBuilder buf = Tessellator.getInstance().getBuilder();
            buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < n; i++) {
                float tw = anim ? 0.75f + 0.25f * (float) Math.sin(t * 1.7 + starP[i]) : 1f;
                float a = (blue * 0.45f + wis * 0.85f) * star * 2f * tw;
                float r = (cb[0] * blue + cw[0] * wis) / (blue + wis), g = (cb[1] * blue + cw[1] * wis) / (blue + wis), b = (cb[2] * blue + cw[2] * wis) / (blue + wis);
                starQuad(buf, m, starX[i], starY[i], starZ[i], starS[i], r, g, b, Math.min(1f, a));
            }
            buf.end();
            WorldVertexBufferUploader.end(buf);
            if (wis > 0.01f && anim && AtmoProfile.of(MoonType.WISDOM).flag("constellations")) constellations(m, t, wis * star * 2f, cw);
            lastLayers++;
        }
    }

    private static void halo(Matrix4f m, float size, float[] c, float a) {
        if (a <= 0.005f) return;
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);
        float y = -99.5f;
        buf.vertex(m, -size, y, size).uv(1, 1).color(c[0], c[1], c[2], Math.min(1f, a)).endVertex();
        buf.vertex(m, size, y, size).uv(0, 1).color(c[0], c[1], c[2], Math.min(1f, a)).endVertex();
        buf.vertex(m, size, y, -size).uv(0, 0).color(c[0], c[1], c[2], Math.min(1f, a)).endVertex();
        buf.vertex(m, -size, y, -size).uv(1, 0).color(c[0], c[1], c[2], Math.min(1f, a)).endVertex();
        buf.end();
        WorldVertexBufferUploader.end(buf);
    }

    /** Ring at angular distance theta from the moon (the moon sits at -Y in the celestial frame). */
    private static void ringAroundMoon(Matrix4f m, float theta, float w, float[] c, float a) {
        if (a <= 0.005f) return;
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
        int n = segments() * 2;
        for (int i = 0; i < n; i++) {
            for (int e = 0; e < 2; e++) {
                double phi = (i + e) * Math.PI * 2 / n;
                float t0 = theta - w, t1 = theta + w;
                float x0 = (float) (Math.sin(t0) * Math.cos(phi)) * 100, y0 = (float) -Math.cos(t0) * 100, z0 = (float) (Math.sin(t0) * Math.sin(phi)) * 100;
                float x1 = (float) (Math.sin(t1) * Math.cos(phi)) * 100, y1 = (float) -Math.cos(t1) * 100, z1 = (float) (Math.sin(t1) * Math.sin(phi)) * 100;
                float xm = (float) (Math.sin(theta) * Math.cos(phi)) * 100, ym = (float) -Math.cos(theta) * 100, zm = (float) (Math.sin(theta) * Math.sin(phi)) * 100;
                if (e == 0) {
                    buf.vertex(m, x0, y0, z0).color(c[0], c[1], c[2], 0f).endVertex();
                    buf.vertex(m, xm, ym, zm).color(c[0], c[1], c[2], a).endVertex();
                } else {
                    buf.vertex(m, xm, ym, zm).color(c[0], c[1], c[2], a).endVertex();
                    buf.vertex(m, x0, y0, z0).color(c[0], c[1], c[2], 0f).endVertex();
                }
                // outer half
            }
        }
        for (int i = 0; i < n; i++) {
            for (int e = 0; e < 2; e++) {
                double phi = (i + e) * Math.PI * 2 / n;
                float t1 = theta + w;
                float x1 = (float) (Math.sin(t1) * Math.cos(phi)) * 100, y1 = (float) -Math.cos(t1) * 100, z1 = (float) (Math.sin(t1) * Math.sin(phi)) * 100;
                float xm = (float) (Math.sin(theta) * Math.cos(phi)) * 100, ym = (float) -Math.cos(theta) * 100, zm = (float) (Math.sin(theta) * Math.sin(phi)) * 100;
                if (e == 0) {
                    buf.vertex(m, xm, ym, zm).color(c[0], c[1], c[2], a).endVertex();
                    buf.vertex(m, x1, y1, z1).color(c[0], c[1], c[2], 0f).endVertex();
                } else {
                    buf.vertex(m, x1, y1, z1).color(c[0], c[1], c[2], 0f).endVertex();
                    buf.vertex(m, xm, ym, zm).color(c[0], c[1], c[2], a).endVertex();
                }
            }
        }
        buf.end();
        WorldVertexBufferUploader.end(buf);
    }

    private static void starQuad(BufferBuilder buf, Matrix4f m, float dx, float dy, float dz, float s, float r, float g, float b, float a) {
        // two tangent vectors perpendicular to the star direction
        float ux, uy, uz;
        if (Math.abs(dy) < 0.9f) { ux = -dz; uy = 0; uz = dx; } else { ux = 0; uy = dz; uz = -dy; }
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        float vx = dy * uz - dz * uy, vy = dz * ux - dx * uz, vz = dx * uy - dy * ux;
        float cx = dx * 100, cy = dy * 100, cz = dz * 100;
        buf.vertex(m, cx - (ux + vx) * s, cy - (uy + vy) * s, cz - (uz + vz) * s).color(r, g, b, a).endVertex();
        buf.vertex(m, cx + (ux - vx) * s, cy + (uy - vy) * s, cz + (uz - vz) * s).color(r, g, b, a).endVertex();
        buf.vertex(m, cx + (ux + vx) * s, cy + (uy + vy) * s, cz + (uz + vz) * s).color(r, g, b, a).endVertex();
        buf.vertex(m, cx - (ux - vx) * s, cy - (uy - vy) * s, cz - (uz - vz) * s).color(r, g, b, a).endVertex();
    }

    /** Each constellation: hidden for a long while, lines appear gradually, hold, fade out. */
    private static void constellations(Matrix4f m, float t, float strength, float[] c) {
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
        for (int k = 0; k < CONST; k++) {
            float period = 75f + k * 11f;
            float ph = ((t + k * 37f) % period);
            float in = 5f, hold = 9f, out = 5f;
            float vis, drawn;
            if (ph < in) { vis = ph / in; drawn = ph / in; }
            else if (ph < in + hold) { vis = 1f; drawn = 1f; }
            else if (ph < in + hold + out) { vis = 1f - (ph - in - hold) / out; drawn = 1f; }
            else continue;
            float a = 0.45f * vis * strength;
            int segs = CONST_STARS - 1;
            float upto = drawn * segs;
            for (int s = 0; s < segs && s < upto; s++) {
                float f = Math.min(1f, upto - s);
                float ax = constX[k][s], ay = constY[k][s], az = constZ[k][s];
                float bx = ax + (constX[k][s + 1] - ax) * f, by = ay + (constY[k][s + 1] - ay) * f, bz = az + (constZ[k][s + 1] - az) * f;
                line(buf, m, ax, ay, az, bx, by, bz, 0.12f, c, a);
            }
            for (int s = 0; s < CONST_STARS; s++) starQuad(buf, m, constX[k][s], constY[k][s], constZ[k][s], 0.42f, c[0], c[1], c[2], Math.min(1f, 0.4f + vis * strength));
        }
        buf.end();
        WorldVertexBufferUploader.end(buf);
    }

    private static void line(BufferBuilder buf, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz, float w, float[] c, float a) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        // offset perpendicular to both the segment and the view direction (the sphere normal)
        float nx = ay * dz - az * dy, ny = az * dx - ax * dz, nz = ax * dy - ay * dx;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-6f) return;
        nx = nx / nl * w; ny = ny / nl * w; nz = nz / nl * w;
        buf.vertex(m, ax * 100 - nx, ay * 100 - ny, az * 100 - nz).color(c[0], c[1], c[2], a).endVertex();
        buf.vertex(m, ax * 100 + nx, ay * 100 + ny, az * 100 + nz).color(c[0], c[1], c[2], a).endVertex();
        buf.vertex(m, bx * 100 + nx, by * 100 + ny, bz * 100 + nz).color(c[0], c[1], c[2], a).endVertex();
        buf.vertex(m, bx * 100 - nx, by * 100 - ny, bz * 100 - nz).color(c[0], c[1], c[2], a).endVertex();
    }

    /** Unused helper kept for clarity of time base in tests. */
    static double millis() {
        return Util.getMillis();
    }
}
