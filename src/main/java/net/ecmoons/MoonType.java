package net.ecmoons;

import corgitaco.enhancedcelestials.api.lunarevent.LunarEvent;
import corgitaco.enhancedcelestials.lunarevent.BloodMoon;
import corgitaco.enhancedcelestials.lunarevent.BlueMoon;
import corgitaco.enhancedcelestials.lunarevent.HarvestMoon;
import net.ecmoons.event.AddonMoonEvent;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * The ten moons of the lineup. The three originals are recognised by Enhanced Celestials' own event classes
 * (so their Super variants map to the same moon); the seven new moons are {@link AddonMoonEvent}s.
 */
public enum MoonType {
    NONE(null),
    BLOOD("enhancedcelestials-blood_moon"),
    BLUE("enhancedcelestials-blue_moon"),
    HARVEST("enhancedcelestials-harvest_moon"),
    MIST("ecmoons-mist_moon"),
    FROST("ecmoons-frost_moon"),
    ARCANE("ecmoons-arcane_moon"),
    PROSPECTOR("ecmoons-prospector_moon"),
    WISDOM("ecmoons-wisdom_moon"),
    QUIET("ecmoons-quiet_moon"),
    DRIFT("ecmoons-drift_moon"),
    REVEALING("ecmoons-revealing_moon"),
    THREAD("ecmoons-thread_moon"),
    WAR("ecmoons-war_moon"),
    GLUTTONY("ecmoons-gluttony_moon");

    public static final MoonType[] MOONS = {BLOOD, BLUE, HARVEST, MIST, FROST, ARCANE, PROSPECTOR, WISDOM, QUIET, DRIFT,
            REVEALING, THREAD, WAR, GLUTTONY};

    /** Event key used by Enhanced Celestials for the default config file of this moon (file name without .json). */
    @Nullable public final String defaultKey;

    MoonType(@Nullable String defaultKey) {
        this.defaultKey = defaultKey;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean isOriginal() {
        return this == BLOOD || this == BLUE || this == HARVEST;
    }

    /** Default key of the Super variant, only for the three original moons. */
    @Nullable
    public String superKey() {
        return isOriginal() ? "enhancedcelestials-super_" + id() + "_moon" : null;
    }

    public ResourceLocation moonTexture() {
        return new ResourceLocation(EcMoons.MOD_ID, "textures/environment/" + id() + "_moon_phases.png");
    }

    /** Animated detail layer; Quiet Moon intentionally has none. */
    @Nullable
    public ResourceLocation detailTexture() {
        return this == QUIET || this == THREAD || this == NONE ? null
                : new ResourceLocation(EcMoons.MOD_ID, "textures/environment/" + id() + "_moon_details.png");
    }

    public static MoonType of(@Nullable LunarEvent event) {
        if (event == null) return NONE;
        if (event instanceof AddonMoonEvent) return ((AddonMoonEvent) event).getMoonType();
        if (event instanceof BloodMoon) return BLOOD;
        if (event instanceof BlueMoon) return BLUE;
        if (event instanceof HarvestMoon) return HARVEST;
        return NONE;
    }

    /** Enhanced Celestials names its stronger variants "super_..." (e.g. enhancedcelestials-super_blood_moon). */
    public static boolean isSuperVariant(@Nullable LunarEvent event) {
        return event != null && event.getKey() != null && event.getKey().contains("super_");
    }

    @Nullable
    public static MoonType byId(String id) {
        for (MoonType t : MOONS) if (t.id().equalsIgnoreCase(id)) return t;
        return null;
    }
}
