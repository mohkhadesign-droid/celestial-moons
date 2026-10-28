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

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Visual preferences for Enhanced Celestials: Ten Moons. These never change gameplay.").push("visuals");
        USE_ADDON_MOON_TEXTURES = b.comment("Draw the ten-moon designs instead of Enhanced Celestials' tinted vanilla moon.")
                .define("useAddonMoonTextures", true);
        RENDER_MOON_DETAILS = b.comment("Draw the extra detail layer (glows, glints, mist wisps, drift fragments).")
                .define("renderMoonDetails", true);
        ANIMATE_MOON_DETAILS = b.comment("Animate the detail layer (pulsing glows, drifting mist, bobbing fragments). False = static.")
                .define("animateMoonDetails", true);
        FROST_OVERLAY = b.comment("Show the subtle frost border while chilled during a Frost Moon.").define("frostOverlay", true);
        FROST_OVERLAY_OPACITY = b.comment("Maximum opacity of the frost border (it only covers the screen edges).")
                .defineInRange("frostOverlayOpacity", 0.35, 0.0, 0.6);
        FROST_PARTICLES = b.comment("Show drifting frost particles outdoors during a Frost Moon.").define("frostParticles", true);
        b.pop();
        SPEC = b.build();
    }

    private ClientConfig() {}
}
