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
    public enum ColorMode { DESATURATE, WASH }
    public static final ForgeConfigSpec.DoubleValue REVEAL_RIPPLE;
    public static final ForgeConfigSpec.DoubleValue REVEAL_AMBER_EDGES;
    public static final ForgeConfigSpec.DoubleValue REVEAL_COLOR;
    public static final ForgeConfigSpec.EnumValue<ColorMode> REVEAL_COLOR_MODE;
    public static final ForgeConfigSpec.BooleanValue THREAD_LINES;
    public static final ForgeConfigSpec.DoubleValue THREAD_SKY, THREAD_PULSES, THREAD_HUD_SCALE;
    public static final ForgeConfigSpec.BooleanValue THREAD_HUD;

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
        b.comment("Revealing Moon visuals. Purely cosmetic: turning them down never changes the moon's gameplay.").push("revealing");
        REVEAL_RIPPLE = b.comment("Brightness of the golden sky ripple at each scan completion (0 = off).")
                .defineInRange("rippleIntensity", 1.0, 0.0, 1.0);
        REVEAL_AMBER_EDGES = b.comment("Amber glow along the screen edges while YOU are revealed (0 = off).")
                .defineInRange("amberEdgeIntensity", 1.0, 0.0, 1.0);
        REVEAL_COLOR = b.comment("World colour loss while YOU are revealed (0 = off). The HUD is never recoloured.")
                .defineInRange("colorIntensity", 1.0, 0.0, 1.0);
        REVEAL_COLOR_MODE = b.comment("DESATURATE = true colour loss with a pale-gold cast (world only, a vanilla post-processing pass).",
                        "WASH = lighter approximation through the light map (pale-gold wash of sky-lit surfaces), for setups where",
                        "post-processing is unavailable. DESATURATE falls back to WASH automatically if the pass cannot load.")
                .defineEnum("colorMode", ColorMode.DESATURATE);
        b.pop();
        b.comment("Thread Moon visuals. Purely cosmetic: they never change the abilities or the damage rules.").push("thread");
        THREAD_LINES = b.comment("Draw the threads between players (your team's support threads and nearby Knotbind tethers).")
                .define("threads", true);
        THREAD_SKY = b.comment("Strength of the Thread Moon sky: strands, the moon's woven threads, its fibres and the cat's reactions (0 = off).")
                .defineInRange("skyIntensity", 1.0, 0.0, 2.0);
        THREAD_PULSES = b.comment("Strength of Lifeline / Guard / Last Stitch pulses on the threads (0 = off).")
                .defineInRange("pulseIntensity", 1.0, 0.0, 1.0);
        THREAD_HUD = b.comment("Show the small Thread Moon indicators near the crosshair and hotbar.").define("hud", true);
        THREAD_HUD_SCALE = b.comment("Size of those indicators.").defineInRange("hudScale", 1.0, 0.5, 2.0);
        b.pop();
        SPEC = b.build();
    }

    private ClientConfig() {}
}
