package net.ecmoons.config;

import net.ecmoons.EcMoons;
import net.minecraft.block.Block;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ITag;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryEntry;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Resolves config lists of "namespace:id" and "#namespace:tag" entries into identity sets, cached per
 * settings snapshot and invalidated when tags reload, so hot paths (random ticks, break speed) are a set lookup.
 */
public final class Matchers {
    private static final Map<Settings, Map<String, Set<?>>> CACHE = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private Matchers() {}

    /** Called when tags or settings change. */
    public static void invalidate() {
        CACHE.clear();
    }

    public static boolean block(Settings s, String key, Block block) {
        return resolve(s, "block:" + key, () -> build(s.list(key), ForgeRegistries.BLOCKS,
                rl -> BlockTags.getAllTags().getTag(rl))).contains(block);
    }

    public static boolean item(Settings s, String key, Item item) {
        return resolve(s, "item:" + key, () -> build(s.list(key), ForgeRegistries.ITEMS,
                rl -> ItemTags.getAllTags().getTag(rl))).contains(item);
    }

    public static boolean entity(Settings s, String entry, EntityType<?> type) {
        if (entry.equals("*")) return true;
        return resolve(s, "entity:" + entry, () -> build(Collections.singletonList(entry), ForgeRegistries.ENTITIES,
                rl -> EntityTypeTags.getAllTags().getTag(rl))).contains(type);
    }

    @SuppressWarnings("unchecked")
    private static <T> Set<T> resolve(Settings s, String cacheKey, java.util.function.Supplier<Set<T>> builder) {
        Map<String, Set<?>> perSettings = CACHE.computeIfAbsent(s, k -> new ConcurrentHashMap<>());
        Set<?> set = perSettings.get(cacheKey);
        if (set == null) {
            set = builder.get();
            perSettings.put(cacheKey, set);
        }
        return (Set<T>) set;
    }

    private static <T extends IForgeRegistryEntry<T>> Set<T> build(List<String> entries, IForgeRegistry<T> registry,
                                                                     Function<ResourceLocation, ITag<T>> tagLookup) {
        Set<T> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String raw : entries) {
            String entry = raw.trim();
            if (entry.isEmpty()) continue;
            try {
                if (entry.startsWith("#")) {
                    ITag<T> tag = tagLookup.apply(new ResourceLocation(entry.substring(1)));
                    if (tag != null) out.addAll(tag.getValues());
                    else warnOnce("Unknown tag in ecmoons config: " + entry);
                } else {
                    ResourceLocation rl = new ResourceLocation(entry);
                    if (registry.containsKey(rl)) out.add(registry.getValue(rl));
                    else warnOnce("Unknown id in ecmoons config (mod not installed?): " + entry);
                }
            } catch (RuntimeException e) {
                warnOnce("Invalid entry in ecmoons config: " + entry + " (" + e.getMessage() + ")");
            }
        }
        return out;
    }

    private static void warnOnce(String message) {
        if (WARNED.add(message)) EcMoons.LOGGER.warn(message);
    }

    /** Ids of entries in a list (for diagnostics). */
    public static Set<String> ids(List<String> entries) {
        return new HashSet<>(entries);
    }
}
