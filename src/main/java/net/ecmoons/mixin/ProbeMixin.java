package net.ecmoons.mixin;

import corgitaco.enhancedcelestials.LunarContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

@Mixin(value = LunarContext.class, remap = false)
public interface ProbeMixin {
    @Accessor("lunarEventsConfigPath")
    Path ecmoons$getEventsPath();
}
