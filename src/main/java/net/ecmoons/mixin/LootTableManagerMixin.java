package net.ecmoons.mixin;

import com.google.gson.JsonElement;
import net.ecmoons.feature.BlueMoonLoot;
import net.minecraft.loot.LootTableManager;
import net.minecraft.profiler.IProfiler;
import net.minecraft.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Append Blue Moon bonus pools to configured loot tables before they are parsed - the same technique Enhanced
 * Celestials uses for its own appended pools. Works for modded tables (e.g. IDAS), which Forge's
 * LootTableLoadEvent does not cover in 1.16.
 */
@Mixin(LootTableManager.class)
public abstract class LootTableManagerMixin {
    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/resources/IResourceManager;Lnet/minecraft/profiler/IProfiler;)V", at = @At("HEAD"))
    private void ecmoons$appendBlueMoonPools(Map<ResourceLocation, JsonElement> tables, IResourceManager manager, IProfiler profiler, CallbackInfo ci) {
        BlueMoonLoot.inject(tables);
    }
}
