package net.ecmoons.feature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializationContext;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Settings;
import net.minecraft.loot.ILootSerializer;
import net.minecraft.loot.LootConditionType;
import net.minecraft.loot.LootContext;
import net.minecraft.loot.conditions.ILootCondition;
import net.minecraft.util.JSONUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Registry;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Blue Moon bonus dungeon loot.
 * <p>
 * For each supported loot table, extra pools are appended when loot tables load (the original pools are never
 * modified). Pool k (k = 1..maxBonusRolls) rolls once only when a Blue Moon is active in that world AND the
 * loot context's Luck is at least k * luckPerBonusRoll. Luck therefore matters explicitly: if whatever generates
 * the chest does not pass the player's Luck into the loot context, no bonus is produced.
 * <p>
 * Lootr (1.16) builds its per-player loot context with {@code withLuck(player.getLuck())} and the player as
 * THIS_ENTITY, generates each player's inventory once on their first opening and stores it per player UUID,
 * so reopening never rerolls and a moon change never alters stored inventories.
 */
public final class BlueMoonLoot {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final ResourceLocation CONDITION_ID = new ResourceLocation(EcMoons.MOD_ID, "moon_luck");
    public static LootConditionType CONDITION_TYPE;

    private static final Map<ResourceLocation, Integer> INJECTED = Collections.synchronizedMap(new TreeMap<>());

    private BlueMoonLoot() {}

    public static void registerCondition() {
        CONDITION_TYPE = Registry.register(Registry.LOOT_CONDITION_TYPE, CONDITION_ID, new LootConditionType(new ConditionSerializer()));
    }

    public static Map<ResourceLocation, Integer> injectedTables() {
        synchronized (INJECTED) {
            return new LinkedHashMap<>(INJECTED);
        }
    }

    // ---- config file ----------------------------------------------------------------------------------------

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(Settings.server().s("blue.bonusLootFile"));
    }

    public static JsonObject load() {
        Path path = file();
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.write(path, GSON.toJson(defaults()).getBytes(StandardCharsets.UTF_8));
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                return new JsonParser().parse(reader).getAsJsonObject();
            }
        } catch (IOException | RuntimeException e) {
            EcMoons.LOGGER.error("Could not read Blue Moon bonus loot file {}; using built-in defaults: {}", path, e.toString());
            return defaults();
        }
    }

    static JsonObject defaults() {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "Blue Moon Luck-sensitive bonus loot. 'tables' maps a loot table id to a pool preset name " +
                "(from 'presets') or to an inline {\"entries\": [...]} object. Bonus pool k rolls once when Blue Moon is active and " +
                "the opener's Luck >= k * luckPerBonusRoll, up to maxBonusRolls. Original pools are untouched. Ores and ingots are " +
                "deliberately absent: ore bonuses belong to Prospector's Moon. Changes apply on /reload or restart.");
        root.addProperty("enabled", true);
        root.addProperty("maxBonusRolls", 2);
        root.addProperty("luckPerBonusRoll", 1.0);
        JsonObject tables = new JsonObject();
        for (String t : new String[]{
                "idas:chests/pillager_fortress/pillager_basic",
                "minecraft:chests/simple_dungeon", "minecraft:chests/abandoned_mineshaft", "minecraft:chests/desert_pyramid",
                "minecraft:chests/jungle_temple", "minecraft:chests/stronghold_corridor", "minecraft:chests/stronghold_crossing",
                "minecraft:chests/stronghold_library", "minecraft:chests/pillager_outpost", "minecraft:chests/woodland_mansion",
                "minecraft:chests/nether_bridge", "minecraft:chests/bastion_other"}) {
            tables.addProperty(t, "dungeon");
        }
        root.add("tables", tables);
        JsonObject presets = new JsonObject();
        JsonObject dungeon = new JsonObject();
        JsonArray entries = new JsonArray();
        entries.add(entry("minecraft:book", 10, "{\"function\":\"minecraft:enchant_randomly\"}"));
        entries.add(entry("minecraft:golden_apple", 8, null));
        entries.add(entry("minecraft:name_tag", 5, null));
        entries.add(entry("minecraft:saddle", 5, null));
        entries.add(entry("minecraft:experience_bottle", 6,
                "{\"function\":\"minecraft:set_count\",\"count\":{\"type\":\"minecraft:uniform\",\"min\":2.0,\"max\":5.0}}"));
        entries.add(entry("minecraft:enchanted_golden_apple", 1, null));
        JsonObject empty = new JsonObject();
        empty.addProperty("type", "minecraft:empty");
        empty.addProperty("weight", 15);
        entries.add(empty);
        dungeon.add("entries", entries);
        presets.add("dungeon", dungeon);
        root.add("presets", presets);
        return root;
    }

    private static JsonObject entry(String item, int weight, String function) {
        JsonObject e = new JsonObject();
        e.addProperty("type", "minecraft:item");
        e.addProperty("name", item);
        e.addProperty("weight", weight);
        if (function != null) {
            JsonArray fns = new JsonArray();
            fns.add(new JsonParser().parse(function));
            e.add("functions", fns);
        }
        return e;
    }

    // ---- injection (called from LootTableManagerMixin before tables are parsed) ---------------------------

    public static void inject(Map<ResourceLocation, JsonElement> tables) {
        synchronized (INJECTED) {
            INJECTED.clear();
        }
        JsonObject cfg = load();
        if (!JSONUtils.getAsBoolean(cfg, "enabled", true)) return;
        int maxRolls = Math.max(0, JSONUtils.getAsInt(cfg, "maxBonusRolls", 2));
        float luckPerRoll = JSONUtils.getAsFloat(cfg, "luckPerBonusRoll", 1.0f);
        JsonObject presets = JSONUtils.getAsJsonObject(cfg, "presets", new JsonObject());
        JsonObject tableCfg = JSONUtils.getAsJsonObject(cfg, "tables", new JsonObject());
        for (Map.Entry<String, JsonElement> e : tableCfg.entrySet()) {
            ResourceLocation id;
            try {
                id = new ResourceLocation(e.getKey());
            } catch (RuntimeException ex) {
                EcMoons.LOGGER.warn("Invalid loot table id in Blue Moon config: {}", e.getKey());
                continue;
            }
            JsonElement table = tables.get(id);
            if (table == null || !table.isJsonObject()) {
                EcMoons.LOGGER.info("Blue Moon bonus loot: table {} is not loaded (mod not installed?) - skipped", id);
                continue;
            }
            JsonObject pool;
            if (e.getValue().isJsonPrimitive()) {
                pool = JSONUtils.getAsJsonObject(presets, e.getValue().getAsString(), null);
            } else {
                pool = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : null;
            }
            if (pool == null || !pool.has("entries")) {
                EcMoons.LOGGER.warn("Blue Moon bonus loot for {} has no entries/preset - skipped", id);
                continue;
            }
            JsonObject tableObj = table.getAsJsonObject();
            JsonArray pools = tableObj.has("pools") ? tableObj.getAsJsonArray("pools") : new JsonArray();
            for (int k = 1; k <= maxRolls; k++) {
                JsonObject bonus = new JsonObject();
                bonus.addProperty("name", EcMoons.MOD_ID + "_blue_moon_bonus_" + k);
                bonus.addProperty("rolls", 1);
                JsonArray conditions = new JsonArray();
                JsonObject cond = new JsonObject();
                cond.addProperty("condition", CONDITION_ID.toString());
                cond.addProperty("moon", MoonType.BLUE.id());
                cond.addProperty("min_luck", k * luckPerRoll);
                conditions.add(cond);
                bonus.add("conditions", conditions);
                bonus.add("entries", new JsonParser().parse(pool.get("entries").toString())); // copy (Gson 2.8.0 has no public deepCopy)
                pools.add(bonus);
            }
            tableObj.add("pools", pools);
            synchronized (INJECTED) {
                INJECTED.put(id, maxRolls);
            }
        }
        EcMoons.LOGGER.info("Blue Moon bonus loot added to {} loot table(s)", INJECTED.size());
    }

    // ---- condition --------------------------------------------------------------------------------------------

    /** True when the given moon's add-on effects are active in the loot context's world and Luck >= min_luck. */
    public static class MoonLuckCondition implements ILootCondition {
        final MoonType moon;
        final float minLuck;

        public MoonLuckCondition(MoonType moon, float minLuck) {
            this.moon = moon;
            this.minLuck = minLuck;
        }

        @Override
        public LootConditionType getType() {
            return CONDITION_TYPE;
        }

        @Override
        public boolean test(LootContext context) {
            return Moons.is(context.getLevel(), moon) && context.getLuck() >= minLuck;
        }
    }

    public static class ConditionSerializer implements ILootSerializer<MoonLuckCondition> {
        @Override
        public void serialize(JsonObject json, MoonLuckCondition value, JsonSerializationContext context) {
            json.addProperty("moon", value.moon.id());
            json.addProperty("min_luck", value.minLuck);
        }

        @Override
        public MoonLuckCondition deserialize(JsonObject json, JsonDeserializationContext context) {
            MoonType type = MoonType.byId(JSONUtils.getAsString(json, "moon", "blue"));
            return new MoonLuckCondition(type == null ? MoonType.BLUE : type, JSONUtils.getAsFloat(json, "min_luck", 1.0f));
        }
    }
}
