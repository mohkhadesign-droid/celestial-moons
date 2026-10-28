package net.ecmoons;

import corgitaco.enhancedcelestials.EnhancedCelestialsWorldData;
import corgitaco.enhancedcelestials.LunarContext;
import corgitaco.enhancedcelestials.api.lunarevent.LunarEvent;
import net.ecmoons.config.Settings;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Single place that answers "which add-on moon effects apply in this world right now?".
 * The answer comes from Enhanced Celestials' own lunar context (synchronised to clients by Enhanced Celestials),
 * filtered by the add-on's enable switches, dimension lists and the Super-variant policy.
 */
public final class Moons {
    private Moons() {}

    @Nullable
    public static LunarContext context(@Nullable World world) {
        if (world == null) return null;
        return ((EnhancedCelestialsWorldData) world).getLunarContext();
    }

    @Nullable
    public static LunarEvent currentEvent(@Nullable World world) {
        LunarContext ctx = context(world);
        return ctx == null ? null : ctx.getCurrentEvent();
    }

    /** The moon whose add-on effects are active in this world, or NONE. */
    public static MoonType active(@Nullable World world) {
        LunarEvent event = currentEvent(world);
        MoonType type = MoonType.of(event);
        if (type == MoonType.NONE) return MoonType.NONE;
        Settings s = Settings.get(world);
        if (!s.moonEnabled(type)) return MoonType.NONE;
        if (MoonType.isSuperVariant(event) && !s.superInherits()) return MoonType.NONE;
        if (!s.dimensionAllowed(type, world.dimension().location())) return MoonType.NONE;
        return type;
    }

    public static boolean is(@Nullable World world, MoonType type) {
        return world != null && active(world) == type;
    }

    /** The moon Enhanced Celestials reports, ignoring add-on filters (used for visuals and diagnostics). */
    public static MoonType reported(@Nullable World world) {
        return MoonType.of(currentEvent(world));
    }
}
