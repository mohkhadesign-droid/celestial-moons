package net.ecmoons.client.atmo;

import corgitaco.enhancedcelestials.api.lunarevent.LunarEvent;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.ClientConfig;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.war.WarArena;
import net.minecraft.client.Minecraft;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.LightType;

/**
 * Client-side atmosphere state. The active moon always comes from Enhanced Celestials' synchronised lunar event
 * (or the War Moon arena dimension, or an admin preview); this class only blends towards it.
 * <p>
 * Every moon has a weight 0..1 that moves towards 1 for the active moon and 0 for all others over
 * atmosphere.transitionSeconds, so starting, ending, cancelling, changing dimension or reconnecting always fades
 * smoothly and never leaves colour behind (a weight of 0 means vanilla/Enhanced Celestials visuals are untouched).
 */
public final class Atmosphere {
    private static final MoonType[] TYPES = MoonType.values();
    private static final float[] weight = new float[TYPES.length];
    private static final float[] prevWeight = new float[TYPES.length];
    private static MoonType target = MoonType.NONE;
    private static boolean superVariant, inArena;
    private static MoonType preview; // admin preview (visual only)
    private static float exposure = 1f; // 0 = deep inside, 1 = under open sky (smoothed)
    private static ClientWorld lastWorld;
    private static long ticks;

    // per-tick cached light-map grading inputs
    static float gradeAmount, gradeBright = 1f, gradeDesat;
    static final float[] gradeTint = {1, 1, 1};
    private static float pulse, pulseDecay;
    private static final float[] pulseColor = {1, 1, 1};

    private Atmosphere() {}

    // ---- settings ---------------------------------------------------------------------------------------------

    public static ClientConfig.Quality quality() {
        try {
            if (!Settings.client().b("atmosphere.enabled")) return ClientConfig.Quality.OFF;
            return ClientConfig.ATMOSPHERE_QUALITY.get();
        } catch (RuntimeException e) {
            return ClientConfig.Quality.MEDIUM;
        }
    }

    public static boolean on(String globalSwitch) {
        return quality() != ClientConfig.Quality.OFF && Settings.client().b("atmosphere." + globalSwitch);
    }

    public static float master() {
        return (float) Settings.client().d("atmosphere.masterIntensity");
    }

    // ---- state -----------------------------------------------------------------------------------------------

    public static void setPreview(MoonType type) {
        preview = type;
    }

    public static MoonType preview() {
        return preview;
    }

    /** Which moon's atmosphere should show right now. */
    static MoonType computeTarget(ClientWorld level) {
        if (level == null || quality() == ClientConfig.Quality.OFF) return MoonType.NONE;
        inArena = level.dimension().equals(WarArena.DIMENSION);
        superVariant = false;
        MoonType t;
        if (preview != null) {
            t = preview;
        } else if (inArena) {
            t = MoonType.WAR;
        } else {
            LunarEvent ev = Moons.currentEvent(level);
            t = MoonType.of(ev);
            superVariant = t != MoonType.NONE && MoonType.isSuperVariant(ev);
            if (t != MoonType.NONE && Settings.client().b("atmosphere.respectDimensions")
                    && !Settings.client().dimensionAllowed(t, level.dimension().location())) t = MoonType.NONE;
        }
        if (t != MoonType.NONE && !AtmoProfile.of(t).enabled) t = MoonType.NONE;
        return t;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientWorld level = mc.level;
        if (level != lastWorld) {
            lastWorld = level;
            AtmoParticles.clear();
            // a new world/dimension starts from the normal night and fades in (no stale colour carried over)
            for (int i = 0; i < weight.length; i++) weight[i] = prevWeight[i] = 0f;
        }
        if (level == null || mc.player == null) {
            preview = null;
            return;
        }
        if (mc.isPaused()) return;
        ticks++;
        target = computeTarget(level);
        float step = 1f / Math.max(1, Settings.client().i("atmosphere.transitionSeconds") * 20);
        for (int i = 0; i < weight.length; i++) {
            prevWeight[i] = weight[i];
            float goal = TYPES[i] == target && target != MoonType.NONE ? 1f : 0f;
            weight[i] = goal > weight[i] ? Math.min(goal, weight[i] + step) : Math.max(goal, weight[i] - step);
        }
        if (ticks % 10 == 0) {
            BlockPos eye = new BlockPos(mc.gameRenderer.getMainCamera().getPosition());
            float e = level.canSeeSky(eye) ? 1f : level.getBrightness(LightType.SKY, eye) / 15f * 0.8f;
            exposureTarget = e;
        }
        exposure += (exposureTarget - exposure) * 0.1f;
        if (pulse > 0) pulse = Math.max(0, pulse - pulseDecay);
        prepareGrade();
        AtmoWar.tick();
        AtmoParticles.tick(mc, level);
        AtmoSounds.tick(mc, level);
    }

    private static float exposureTarget = 1f;

    public static float weight(MoonType t, float pt) {
        if (t == MoonType.NONE) return 0f;
        int i = t.ordinal();
        return MathHelper.lerp(pt, prevWeight[i], weight[i]);
    }

    public static float weight(MoonType t) {
        return t == MoonType.NONE ? 0f : weight[t.ordinal()];
    }

    /** Effective strength of a moon's atmosphere: weight x profile intensity x master x super/arena boost. */
    public static float strength(MoonType t, float pt) {
        float w = weight(t, pt);
        if (w <= 0f) return 0f;
        AtmoProfile p = AtmoProfile.of(t);
        float s = w * p.intensity * master();
        if (t == target && superVariant) s *= 1.15f;
        if (t == MoonType.WAR && inArena) s *= (float) p.value("arenaBoost", 1.3);
        return Math.min(1.5f, s);
    }

    public static boolean anyActive() {
        for (float w : weight) if (w > 0f) return true;
        return false;
    }

    public static MoonType target() {
        return target;
    }

    public static boolean inArena() {
        return inArena;
    }

    public static boolean superVariant() {
        return superVariant;
    }

    public static float exposure() {
        return exposure;
    }

    public static long ticks() {
        return ticks;
    }

    /** 0 by day, 1 at night (from the vanilla star brightness, so it follows /time and the arena's fixed night). */
    public static float night(ClientWorld level, float pt) {
        return MathHelper.clamp(level.getStarBrightness(pt) * 2.2f, 0f, 1f);
    }

    /** A brief, smooth brightening of outdoor light (Revealing scan, War summon). Never a flash. */
    public static void pulse(float amount, int ticks, float[] color) {
        pulse = Math.max(pulse, amount);
        pulseDecay = amount / Math.max(1, ticks);
        System.arraycopy(color, 0, pulseColor, 0, 3);
    }

    public static float pulseAmount() {
        return pulse;
    }

    // ---- sky / fog colour --------------------------------------------------------------------------------------

    public static Vector3d skyColor(ClientWorld level, Vector3d vanilla, float pt) {
        if (!on("enableSkyTint")) return vanilla;
        float night = night(level, pt);
        if (night <= 0f) return vanilla;
        float rain = 1f - level.getRainLevel(pt) * 0.4f;
        double r = vanilla.x, g = vanilla.y, b = vanilla.z;
        for (MoonType t : TYPES) {
            float k = strength(t, pt);
            if (k <= 0f) continue;
            AtmoProfile p = AtmoProfile.of(t);
            float a = Math.min(1f, k * night * rain);
            float[] c = p.sky;
            float lift = AtmoWar.skyDarken(t);
            r = MathHelper.lerp(a, r, c[0] * 0.75f * lift);
            g = MathHelper.lerp(a, g, c[1] * 0.75f * lift);
            b = MathHelper.lerp(a, b, c[2] * 0.75f * lift);
        }
        return new Vector3d(r, g, b);
    }

    /** Blend the fog/horizon colour (called from the FogColors event). Returns {r,g,b}. */
    public static float[] fogColor(ClientWorld level, float r, float g, float b, float pt) {
        if (!on("enableSkyTint")) return null;
        float night = night(level, pt);
        if (night <= 0f) return null;
        boolean changed = false;
        for (MoonType t : TYPES) {
            float k = strength(t, pt);
            if (k <= 0f) continue;
            AtmoProfile p = AtmoProfile.of(t);
            float a = Math.min(0.9f, k * night * 0.9f);
            float[] c = p.horizon;
            r = MathHelper.lerp(a, r, c[0] * 0.42f);
            g = MathHelper.lerp(a, g, c[1] * 0.42f);
            b = MathHelper.lerp(a, b, c[2] * 0.42f);
            changed = true;
        }
        return changed ? new float[]{r, g, b} : null;
    }

    /** Coloured distance haze: 0 = none, 1 = strongest. Outdoors only (scaled by sky exposure). */
    public static float haze(ClientWorld level, float pt) {
        if (!on("enableFog")) return 0f;
        float night = night(level, pt);
        float h = 0f;
        for (MoonType t : TYPES) {
            float k = strength(t, pt);
            if (k <= 0f) continue;
            h += k * AtmoProfile.of(t).fog * (1f + AtmoWar.turbulence() * (t == MoonType.WAR ? 0.4f : 0f));
        }
        return MathHelper.clamp(h * night * (0.35f + 0.65f * exposure), 0f, 1f);
    }

    // ---- light map grading ---------------------------------------------------------------------------------------

    private static void prepareGrade() {
        Minecraft mc = Minecraft.getInstance();
        ClientWorld level = mc.level;
        gradeAmount = 0f;
        gradeBright = 1f;
        gradeDesat = 0f;
        gradeTint[0] = gradeTint[1] = gradeTint[2] = 1f;
        if (level == null || !on("enableLightTint")) return;
        float night = night(level, 1f);
        if (night <= 0f && pulse <= 0f) return;
        float total = 0f;
        float tr = 0, tg = 0, tb = 0, bright = 0, desat = 0, amount = 0;
        for (MoonType t : TYPES) {
            float k = strength(t, 1f);
            if (k <= 0f) continue;
            AtmoProfile p = AtmoProfile.of(t);
            float[] c = p.light;
            float lum = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2];
            lum = Math.max(lum, 0.05f);
            tr += k * Math.min(2f, c[0] / lum);
            tg += k * Math.min(2f, c[1] / lum);
            tb += k * Math.min(2f, c[2] / lum);
            bright += k * (p.brightness - 1f);
            desat += k * p.desaturate;
            amount += k * p.tint;
            total += k;
        }
        if (total > 0f) {
            gradeTint[0] = tr / total;
            gradeTint[1] = tg / total;
            gradeTint[2] = tb / total;
            gradeAmount = Math.min(1f, amount * night);
            gradeBright = 1f + bright * night;
            gradeDesat = Math.min(1f, desat * night);
        }
    }

    /** Light-map pixel (x = block light, y = sky light, ABGR). Only the sky-light part is graded. */
    public static int gradeLightmap(int blockLevel, int skyLevel, int abgr) {
        if (gradeAmount <= 0f && gradeBright == 1f && gradeDesat <= 0f && pulse <= 0f) return abgr;
        float sky = skyLevel / 15f;
        float w = sky * sky * (1f - blockLevel / 15f * 0.75f);
        if (w <= 0.001f) return abgr;
        float r = (abgr & 255) / 255f, g = ((abgr >> 8) & 255) / 255f, b = ((abgr >> 16) & 255) / 255f;
        float a = gradeAmount * w;
        r *= 1f + (gradeTint[0] - 1f) * a;
        g *= 1f + (gradeTint[1] - 1f) * a;
        b *= 1f + (gradeTint[2] - 1f) * a;
        float br = 1f + (gradeBright - 1f) * w;
        r *= br;
        g *= br;
        b *= br;
        if (gradeDesat > 0f) {
            float grey = 0.299f * r + 0.587f * g + 0.114f * b;
            float d = gradeDesat * w;
            r += (grey - r) * d;
            g += (grey - g) * d;
            b += (grey - b) * d;
        }
        if (pulse > 0f) {
            float p = pulse * w * 0.35f;
            r += pulseColor[0] * p;
            g += pulseColor[1] * p;
            b += pulseColor[2] * p;
        }
        int ri = MathHelper.clamp((int) (r * 255f), 0, 255), gi = MathHelper.clamp((int) (g * 255f), 0, 255),
                bi = MathHelper.clamp((int) (b * 255f), 0, 255);
        return (abgr & 0xFF000000) | bi << 16 | gi << 8 | ri;
    }

    /** For tests and diagnostics. */
    public static String describe() {
        StringBuilder sb = new StringBuilder("target=" + target + (preview != null ? " (preview)" : "") + (inArena ? " arena" : "")
                + " quality=" + quality() + " exposure=" + String.format("%.2f", exposure) + " weights:");
        for (MoonType t : TYPES) if (weight[t.ordinal()] > 0) sb.append(' ').append(t.id()).append('=').append(String.format("%.2f", weight[t.ordinal()]));
        sb.append(" particles=").append(AtmoParticles.count()).append('/').append(AtmoParticles.cap());
        return sb.toString();
    }
}
