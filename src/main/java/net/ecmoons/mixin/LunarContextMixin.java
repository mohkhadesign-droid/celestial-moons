package net.ecmoons.mixin;

import corgitaco.enhancedcelestials.LunarContext;
import corgitaco.enhancedcelestials.api.lunarevent.LunarEvent;
import net.ecmoons.event.AddonMoonEvent;
import net.ecmoons.event.DefaultEvents;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.Map;

/**
 * Integration with Enhanced Celestials' per-world event configuration.
 * <ul>
 *   <li>Before EC reads the event files of an existing world, write any missing default file for the seven new
 *       moons (EC only writes defaults into an empty folder).</li>
 *   <li>After the server-side read, remove events the add-on config disables, so they never enter the forecast
 *       (EC then drops stale forecast entries for missing keys by itself).</li>
 *   <li>Tell each add-on event which dimension it belongs to (used for per-dimension settings).</li>
 * </ul>
 */
@Mixin(value = LunarContext.class, remap = false)
public abstract class LunarContextMixin {
    @Shadow @Final private Path lunarEventsConfigPath;
    @Shadow @Final private Map<String, LunarEvent> lunarEvents;
    @Shadow @Final private ResourceLocation worldID;

    @Inject(method = "handleEventConfigs", at = @At("HEAD"))
    private void ecmoons$writeMissingDefaults(boolean isClient, CallbackInfo ci) {
        if (!isClient) DefaultEvents.writeMissingFiles(lunarEventsConfigPath);
    }

    @Inject(method = "handleEventConfigs", at = @At("RETURN"))
    private void ecmoons$applyEnableSwitches(boolean isClient, CallbackInfo ci) {
        if (!isClient) DefaultEvents.removeDisabled(lunarEvents, worldID);
        for (LunarEvent event : lunarEvents.values()) {
            if (event instanceof AddonMoonEvent) ((AddonMoonEvent) event).setDimension(worldID);
        }
    }
}
