package net.ecmoons.client.atmo;

import net.ecmoons.MoonType;
import net.ecmoons.config.Settings;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * One moon's atmosphere profile, read from the server's synchronised settings ([atmosphere_&lt;moon&gt;] in
 * ecmoons-common.toml). Cached per settings snapshot, so a /ecmoons reload takes effect immediately.
 */
public final class AtmoProfile {
    public final MoonType type;
    public final boolean enabled, particles, skyAnimation;
    public final float[] sky, horizon, light, glow, accent;
    public final float intensity, tint, fog, brightness, desaturate, moonGlow, density, animation, soundVolume;
    private final Settings settings;

    private static final Map<Settings, EnumMap<MoonType, AtmoProfile>> CACHE = new IdentityHashMap<>();

    private AtmoProfile(Settings s, MoonType type) {
        this.settings = s;
        this.type = type;
        String p = "atmosphere_" + type.id() + ".";
        enabled = s.b(p + "enabled");
        particles = s.b(p + "particles");
        skyAnimation = s.b(p + "skyAnimation");
        sky = hex(s.s(p + "skyColor"));
        horizon = hex(s.s(p + "horizonColor"));
        light = hex(s.s(p + "lightColor"));
        glow = hex(s.s(p + "glowColor"));
        accent = hex(s.s(p + "accentColor"));
        intensity = (float) s.d(p + "intensity");
        tint = (float) s.d(p + "tintStrength");
        fog = (float) s.d(p + "fogStrength");
        brightness = (float) s.d(p + "brightness");
        desaturate = (float) s.d(p + "desaturate");
        moonGlow = (float) s.d(p + "moonGlow");
        density = (float) s.d(p + "particleDensity");
        animation = (float) s.d(p + "animationIntensity");
        soundVolume = (float) s.d(p + "soundVolume");
    }

    public static synchronized AtmoProfile of(MoonType type) {
        Settings s = Settings.client();
        if (CACHE.size() > 4 && !CACHE.containsKey(s)) CACHE.clear();
        return CACHE.computeIfAbsent(s, k -> new EnumMap<>(MoonType.class)).computeIfAbsent(type, t -> new AtmoProfile(s, t));
    }

    /** A moon-specific extra switch (e.g. "coldBreath"); false if the key does not exist. */
    public boolean flag(String key) {
        try {
            return settings.b("atmosphere_" + type.id() + "." + key);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public double value(String key, double fallback) {
        try {
            return settings.d("atmosphere_" + type.id() + "." + key);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static float[] hex(String s) {
        try {
            int v = Integer.parseInt(s.trim().replace("#", "").replace("0x", ""), 16);
            return new float[]{((v >> 16) & 255) / 255f, ((v >> 8) & 255) / 255f, (v & 255) / 255f};
        } catch (RuntimeException e) {
            return new float[]{0.5f, 0.5f, 0.5f};
        }
    }
}
