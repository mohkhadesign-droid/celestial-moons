package net.ecmoons.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/ecmoons-client.toml — purely visual, per-player preferences. Gameplay values come from the server. */
public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue USE_ADDON_MOON_TEXTURES;
    public static final ForgeConfigSpec.BooleanValue RENDER_MOON_DETAILS;
    public static final ForgeConfigSpec.BooleanValue ANIMATE_MOON_DETAILS;
    public static final ForgeConfigSpec.BooleanValue FROST_OVERLAY;
    public static final ForgeConfigSpec.DoubleValue FROST_OVERLAY_OPACITY;
    public static final ForgeConfigSpec.BooleanValue FROST_PARTICLES;
    public enum Quality { OFF, LOW, MEDIUM, HIGH }
    public static final ForgeConfigSpec.EnumValue<Quality> ATMOSPHERE_QUALITY;
    public static final ForgeConfigSpec.DoubleValue ATMOSPHERE_SOUND_VOLUME;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Visual preferences for Enhanced Celestials: Fourteen Moons. These never change gameplay.").push("visuals");
        USE_ADDON_MOON_TEXTURES = b.comment("Draw the ten-moon designs instead of Enhanced Celestials' tinted vanilla moon.")
                .define("useAddonMoonTextures", true);
        RENDER_MOON_DETAILS = b.comment("Draw the extra detail layer (glows, glints, mist wisps, drift fragments).")
                .define("renderMoonDetails", true);
        ANIMATE_MOON_DETAILS = b.comment("Animate the detail layer (pulsing glows, drifting mist, bobbing fragments). False = static.")
                .define("animateMoonDetails", true);
        FROST_OVERLAY = b.comment("Show the subtle frost border while chilled during a Frost Moon.").define("frostOverlay", true);
        FROST_OVERLAY_OPACITY = b.comment("Maximum opacity of the frost border (it only covers the screen edges).")
                .defineInRange("frostOverlayOpacity", 0.35, 0.0, 0.6);
        FROST_PARTICLES = b.comment("Show drifting ice crystals outdoors during a Frost Moon (part of the atmosphere particle budget).").define("frostParticles", true);
        b.pop();
        b.comment("Moon atmosphere (sky, haze, light tint, particles, sky animations). Never changes gameplay.").push("atmosphere");
        ATMOSPHERE_QUALITY = b.comment("OFF = no atmosphere rendering (moon textures, warnings, reveal markers and the Thread HUD stay).",
                        "LOW = colours, haze, halos and simple sky layers, few particles. MEDIUM = recommended. HIGH = most detail.")
                .defineEnum("quality", Quality.MEDIUM);
        ATMOSPHERE_SOUND_VOLUME = b.comment("Your volume for the moons' ambient sounds (multiplies the server's setting).")
                .defineInRange("soundVolume", 1.0, 0.0, 1.0);
        b.pop();
        SPEC = b.build();
    }

    private ClientConfig() {}
}
