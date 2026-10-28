package net.ecmoons.config;

import com.google.common.collect.ImmutableList;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * config/ecmoons-common.toml — what each moon does. When and how often a moon happens (chance, spacing,
 * phases, notifications, sky colour) stays in Enhanced Celestials' per-world event JSON files.
 * <p>
 * Every value is registered under a dotted key (e.g. "mist.fogEnd") so it can be snapshotted into
 * {@link Settings} and synchronised to clients.
 */
public final class MoonConfig {
    public static final ForgeConfigSpec SPEC;
    /** Dotted key -> config value, in definition order. */
    public static final Map<String, ForgeConfigSpec.ConfigValue<?>> VALUES = new LinkedHashMap<>();

    private static ForgeConfigSpec.Builder B;
    private static String section = "";

    private MoonConfig() {}

    static {
        B = new ForgeConfigSpec.Builder();

        push("general", "Settings shared by every moon.");
        bool("superVariantsInheritCustomFeatures", true,
                "Enhanced Celestials' Super Blood/Blue/Harvest Moons keep their own (stronger) Enhanced Celestials values",
                "(spawn cap, Luck level, crop drop multiplier, moon size). When this is true they ALSO get the add-on's",
                "custom features of their base moon at the SAME strength (they are never automatically doubled).",
                "When false, Super variants only have their original Enhanced Celestials behaviour.");
        list("disabledEventKeys", ImmutableList.of(),
                "Extra Enhanced Celestials event keys (config file names without .json) to remove from the forecast.",
                "Applied when a world loads (restart required). Prefer the per-moon 'enabled' switches.");
        pop();

        moon("blood", "Blood Moon - combat and rare drops. Keeps Enhanced Celestials' hostile cap x3 and blocked sleeping.", true);
        dbl("rareDropMultiplier", 1.25, 1.0, 10.0,
                "Multiplier applied to the chance of each configured rare drop rule (1.25 = +25% multiplicative: 4% -> 5%).");
        list("rareDropRules", ImmutableList.of(
                        "minecraft:wither_skeleton|minecraft:wither_skeleton_skull|0.025|0.01",
                        "minecraft:zombie|minecraft:iron_ingot,minecraft:carrot,minecraft:potato|0.025|0.01",
                        "minecraft:husk|minecraft:iron_ingot,minecraft:carrot,minecraft:potato|0.025|0.01",
                        "minecraft:zombie_villager|minecraft:iron_ingot,minecraft:carrot,minecraft:potato|0.025|0.01",
                        "minecraft:drowned|minecraft:gold_ingot|0.11|0.02",
                        "minecraft:zombified_piglin|minecraft:gold_ingot|0.025|0.01"),
                "Rare drop rules: entity|item[,item...]|baseChance|chancePerLootingLevel",
                "baseChance must match the mob's loot table (the vanilla defaults above are copied from 1.16.5 tables).",
                "When none of the listed items dropped, a second chance q = (m-1)p/(1-p) adds ONE of them, so the overall",
                "chance becomes exactly m*p. Ordinary drops are never re-rolled or duplicated.",
                "entity may be an entity id, #entity_type_tag, or * (any monster).");
        bool("requirePlayerKill", true, "Only monsters killed by a real (non-fake) player qualify.");
        list("eligibleSpawnOrigins", ImmutableList.of("NATURAL", "CHUNK_GENERATION", "PATROL", "JOCKEY", "REINFORCEMENT", "EVENT", "STRUCTURE"),
                "Spawn origins (vanilla SpawnReason names) that qualify. SPAWNER is excluded by default so mob-spawner farms",
                "get no rare-drop bonus. Origins are recorded when a mob spawns (see README).");
        bool("untrackedMobsEligible", true,
                "Mobs with no recorded origin (spawned before this add-on was installed, or by a mod that bypasses Forge's",
                "spawn events) qualify when true.");
        pop();

        moon("blue", "Blue Moon - Luck, fishing and dungeon loot. Keeps Enhanced Celestials' Luck effect.", true);
        dbl("fishingWaitMultiplier", 0.70, 0.05, 1.0, "Multiplier for fishing bite waiting time (0.70 = 30% shorter).");
        str("bonusLootFile", "ecmoons/blue_moon_bonus_loot.json",
                "Path (inside the config folder) of the Luck-sensitive bonus loot definition for supported dungeon tables.");
        pop();

        moon("harvest", "Harvest Moon - farming and food. Keeps Enhanced Celestials' crop drop multiplier.", true);
        dbl("growthMultiplier", 2.0, 1.0, 4.0,
                "Random-tick growth multiplier for the configured crops only (2.0 = every crop random tick happens twice).");
        list("growthBlocks", ImmutableList.of("#minecraft:crops", "minecraft:nether_wart", "minecraft:cocoa",
                        "minecraft:sweet_berry_bush", "minecraft:sugar_cane", "minecraft:cactus", "minecraft:bamboo"),
                "Blocks whose growth is accelerated: block ids or #block_tags. Add modded crops here.");
        dbl("exhaustionMultiplier", 0.75, 0.0, 1.0, "Hunger exhaustion multiplier (0.75 = 25% less).");
        bool("requireMatureCrops", true,
                "Only double crop drops (Enhanced Celestials' feature) when the broken crop is fully grown. Prevents the",
                "plant-and-break-immediately duplication loop (e.g. an age-0 carrot). Right-click harvesting that pops",
                "drops without breaking the block is unaffected.");
        pop();

        moon("mist", "Mist Moon - visibility and stealth. Damage and capture speed are never changed.", false);
        dbl("fogStart", 6.0, 0.0, 64.0, "Distance where the mist starts (blocks).");
        dbl("fogEnd", 30.0, 8.0, 128.0, "Distance where the mist is opaque (blocks). 24-32 gives the intended useful outdoor visibility.");
        dbl("fogColorBlend", 0.65, 0.0, 1.0, "How strongly fog colour is pulled towards mist grey.");
        intv("fadeSeconds", 10, 1, 120, "Fade-in/fade-out time when the moon rises or sets.");
        dbl("nameplateMaxDistance", 22.0, 4.0, 64.0, "Other players' name tags are hidden beyond this distance while the mist is thick.");
        dbl("footstepRangeMultiplier", 0.5, 0.1, 1.0, "Footstep sound range multiplier (0.5 = heard at half the usual distance).");
        pop();

        moon("frost", "Frost Moon - exposure and preparation. Terrain, water and crops are never changed.", false);
        intv("exposureDelaySeconds", 20, 1, 600, "Seconds under open sky before the slowdown applies.");
        dbl("slowdown", 0.15, 0.0, 0.9, "Movement slowdown while chilled (0.15 = 15%). Applied as its own attribute modifier.");
        intv("warmthSeconds", 45, 0, 600, "Warmth after eating; eating again refreshes (does not stack) the timer.");
        intv("heatSourceRadius", 4, 0, 8, "Radius (blocks) searched for heat sources.");
        list("heatSources", ImmutableList.of("minecraft:campfire", "minecraft:soul_campfire", "minecraft:fire", "minecraft:soul_fire",
                        "minecraft:lava", "minecraft:magma_block", "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker"),
                "Heat source blocks (ids or #tags).");
        bool("requireLitState", true, "Blocks with a 'lit' property (campfires, furnaces) only count when lit.");
        list("insulatingItems", ImmutableList.of("minecraft:leather_helmet", "minecraft:leather_chestplate",
                        "minecraft:leather_leggings", "minecraft:leather_boots"),
                "Worn items that protect against the cold (ids or #item_tags). Add modded fur/wool armour here.");
        intv("delayBonusPerPieceSeconds", 10, 0, 600, "Extra exposure seconds granted by each insulating piece worn.");
        bool("fullSetImmune", true, "Wearing four insulating pieces prevents the slowdown entirely.");
        pop();

        moon("arcane", "Arcane Moon - enchanting and repairs. All costs are computed and charged on the server.", false);
        dbl("levelCostMultiplier", 0.75, 0.1, 1.0, "XP level cost multiplier for enchanting and anvils (0.75 = 25% cheaper).");
        intv("minLevelCost", 1, 0, 10, "A discounted cost never drops below this (unless the original cost was lower).");
        dbl("repairBonusMultiplier", 1.25, 1.0, 3.0, "Durability restored per repair material in an anvil (1.25 = +25%).");
        bool("affectEnchanting", true, "Discount enchanting-table level consumption.");
        bool("affectAnvil", true, "Discount anvil level costs and boost material repairs.");
        pop();

        moon("prospector", "Prospector's Moon - mining. The only moon with ore bonuses.", false);
        dbl("bonusChance", 0.25, 0.0, 1.0, "Chance that manually mining an eligible natural ore yields one additional resource.");
        dbl("miningSpeedMultiplier", 1.25, 1.0, 4.0, "Mining speed multiplier for eligible natural stone and ores.");
        list("oreBonuses", ImmutableList.of(
                        "minecraft:coal_ore=minecraft:coal", "minecraft:iron_ore=minecraft:iron_ore", "minecraft:gold_ore=minecraft:gold_ore",
                        "minecraft:diamond_ore=minecraft:diamond", "minecraft:emerald_ore=minecraft:emerald",
                        "minecraft:lapis_ore=minecraft:lapis_lazuli", "minecraft:redstone_ore=minecraft:redstone",
                        "minecraft:nether_quartz_ore=minecraft:quartz", "minecraft:nether_gold_ore=minecraft:gold_nugget"),
                "Ore -> bonus resource mappings: block=item or block=item*count. Add modded ores here.",
                "The bonus is a flat +1 resource (or the configured count): it is NOT multiplied by Fortune and does not",
                "change Fortune's own result. Silk Touch disables it.");
        list("speedBlocks", ImmutableList.of("#forge:ores", "#forge:stone", "minecraft:netherrack", "minecraft:basalt",
                        "minecraft:blackstone", "minecraft:end_stone"),
                "Natural stone/ore blocks that mine faster (ids or #tags). Player-placed blocks always break at normal speed.");
        str("legacyChunkPolicy", "ALLOW",
                "Chunks saved before this add-on was installed have no placement history.",
                "ALLOW: treat their blocks as natural. DENY: no ore bonus/XP bonus/speed in those chunks.");
        pop();

        moon("wisdom", "Wisdom Moon - experience.", false);
        dbl("mobXpMultiplier", 2.0, 1.0, 10.0, "XP multiplier for naturally spawned hostile monsters killed by players.");
        dbl("oreXpMultiplier", 2.0, 1.0, 10.0, "XP multiplier for manually mined natural XP ores.");
        list("xpOreBlocks", ImmutableList.of("minecraft:coal_ore", "minecraft:diamond_ore", "minecraft:emerald_ore",
                        "minecraft:lapis_ore", "minecraft:redstone_ore", "minecraft:nether_quartz_ore", "minecraft:nether_gold_ore"),
                "XP-bearing ores that qualify (ids or #tags).");
        list("eligibleSpawnOrigins", ImmutableList.of("NATURAL", "CHUNK_GENERATION", "PATROL", "JOCKEY", "REINFORCEMENT"),
                "Spawn origins that count as natural. SPAWNER, SPAWN_EGG, COMMAND, BREEDING, CONVERSION etc. never qualify.");
        bool("untrackedMobsEligible", false, "Mobs with no recorded origin qualify when true (default false: no XP farming loopholes).");
        pop();

        moon("quiet", "Quiet Moon - recovery. Never touches difficulty, PvP, claims or raid/capture rules.", false);
        dbl("monsterCapMultiplier", 0.35, 0.0, 1.0, "Natural hostile mob cap multiplier (0.35 = 65% fewer new monsters). Existing monsters stay.");
        intv("combatCooldownSeconds", 12, 0, 600, "Seconds without dealing/receiving combat damage before bonus regeneration starts.");
        intv("minFoodLevel", 18, 0, 20, "Minimum food level (out of 20) to count as well fed.");
        intv("regenIntervalTicks", 40, 5, 1200, "Ticks between bonus heals.");
        dbl("regenAmount", 1.0, 0.0, 20.0, "Health restored per bonus heal (1.0 = half a heart).");
        pop();

        moon("drift", "Drift Moon - movement. Sprint speed, attack speed and projectiles are never changed.", false);
        dbl("jumpBoost", 0.07, 0.0, 0.3,
                "Extra upward jump velocity (vanilla jump is 0.42; Jump Boost I adds 0.1). 0.07 keeps 2-block walls unclimbable.");
        dbl("gravityMultiplier", 0.6, 0.1, 1.0, "Gravity multiplier while falling (players only).");
        dbl("fallDamageMultiplier", 0.5, 0.0, 1.0, "Fall damage multiplier (0.5 = 50% less).");
        dbl("jumpExhaustionMultiplier", 0.5, 0.0, 1.0, "Hunger exhaustion from jumping multiplier.");
        pop();


        moon("revealing", "Revealing Moon - the eye searches and reveals where players were standing.", false);
        intv("scanIntervalSeconds", 60, 10, 3600, "Seconds between scans (one \"The eye is searching\" warning per interval, the scan follows after countdownSeconds).");
        intv("countdownSeconds", 5, 1, 60, "Warning countdown before each scan.");
        intv("markerSeconds", 8, 1, 120, "How long the eye markers stay visible at the recorded positions.");
        intv("revealRadius", 256, 16, 2048, "Viewers only receive markers within this horizontal distance (blocks).");
        bool("includeCreative", false, "Reveal players in creative mode too (spectators are never revealed).");
        list("protectedAreas", ImmutableList.of(),
                "Areas where players are never revealed: dimension|x1,z1|x2,z2 (e.g. minecraft:overworld|-100,-100|100,100).",
                "Claims are NOT automatically protected. Players with the scoreboard tag ecmoons_reveal_exempt are also skipped.");
        intv("blinkCloseTicks", 6, 2, 40, "Ticks for the eyelids to close (and again to open) during a blink.");
        intv("blinkHoldTicks", 4, 0, 40, "Ticks the eye stays shut in an idle blink.");
        intv("idleBlinkMinSeconds", 6, 1, 600, "Shortest gap between idle blinks.");
        intv("idleBlinkMaxSeconds", 14, 1, 600, "Longest gap between idle blinks.");
        pop();

        moon("thread", "Thread Moon - teammates bound together share protection. Teams are /moonteams (admin-assigned).", false);
        dbl("bondRange", 12.0, 2.0, 64.0, "Teammates within this distance (and with a clear line of sight) bond.");
        dbl("reductionPerTeammate", 0.05, 0.0, 0.5, "Damage reduction per bonded teammate (0.05 = 5%).");
        dbl("maxReduction", 0.15, 0.0, 0.9, "Cap on the total reduction.");
        intv("fadeSeconds", 3, 0, 60, "After teammates separate, the bonus fades to zero over this many seconds.");
        bool("requireLineOfSight", true, "Bonding needs a clear line between the players' eyes (no walls or floors between them).");
        list("excludedDamageTypes", ImmutableList.of("starve", "drown", "outOfWorld", "inWall", "cramming", "fall", "lava", "inFire", "onFire", "hotFloor", "magic", "wither"),
                "Damage source ids (DamageSource msgId) that are never reduced.");
        pop();

        moon("war", "War Moon - a temporary last-player-standing tournament in a separate arena dimension.", false);
        intv("warningSeconds", 120, 10, 1800, "Warning before the tournament (reminders at 60, 30 and 10 seconds).");
        intv("minParticipants", 2, 1, 64, "Fewer eligible players than this cancels the tournament for this night.");
        intv("maxParticipants", 16, 2, 16, "At most this many players are enrolled (one per starting cell).");
        intv("prepSeconds", 10, 3, 120, "Countdown inside the cells before the gates open.");
        intv("matchSeconds", 360, 30, 3600, "Time limit before sudden death starts.");
        intv("suddenDeathShrinkSeconds", 8, 1, 120, "Sudden death: the safe square shrinks by 2 blocks every this many seconds.");
        intv("celebrationSeconds", 10, 0, 120, "Time between the winner announcement and everyone's return.");
        intv("deferMinutes", 2, 1, 60, "If a Flan Warfare flag capture is in progress, the tournament is postponed by this long.");
        intv("maxDeferrals", 5, 0, 50, "After this many postponements the tournament is cancelled for this night.");
        list("excludedPlayers", ImmutableList.of(), "Player names or UUIDs never enrolled. The tag ecmoons_war_exempt does the same.");
        list("kit", ImmutableList.of("head=minecraft:iron_helmet", "chest=minecraft:iron_chestplate", "legs=minecraft:iron_leggings",
                        "feet=minecraft:iron_boots", "offhand=minecraft:shield", "minecraft:iron_sword", "minecraft:bow",
                        "minecraft:arrow*16", "minecraft:cooked_beef*16"),
                "Equal kit: slot=item or item*count (slots: head, chest, legs, feet, offhand; others go to the inventory).");
        list("accessoryCapabilities", ImmutableList.of("curios:inventory"),
                "Player capabilities that hold equipment (accessory mods). Their items are saved, emptied for the match and restored.",
                "If such a capability cannot be saved, that player is not enrolled and told why.");
        list("ignoredCapabilities", ImmutableList.of(),
                "Player capabilities that contain item data but are NOT usable equipment (left untouched during the match).",
                "Any other capability that holds items blocks that player from enrolling, so nothing can be lost or duplicated.");
        intv("arenaFloorSize", 48, 24, 96, "Fighting floor size (blocks, square).");
        intv("arenaWallHeight", 12, 6, 32, "Outer wall height.");
        str("arenaTemplate", "", "Optional structure template (namespace:path, e.g. from a structure block) to place instead of the",
                "built-in arena. When set, arenaCells must list 'x,y,z' start positions relative to the template origin,",
                "and arenaSpectatorPos the viewing position.");
        list("arenaCells", ImmutableList.of(), "Start positions for a custom arenaTemplate (relative x,y,z).");
        str("arenaSpectatorPos", "", "Viewing position for a custom arenaTemplate (relative x,y,z).");
        intv("championMinutes", 30, 1, 1440, "War Champion reward duration (minutes of online time). Winning again refreshes it.");
        pop();

        moon("gluttony", "Gluttony Moon - every different food eaten adds a temporary heart.", false);
        intv("maxBonusHearts", 4, 1, 20, "Maximum extra hearts.");
        list("foodCategories", ImmutableList.of(
                        "beef=minecraft:beef,minecraft:cooked_beef", "pork=minecraft:porkchop,minecraft:cooked_porkchop",
                        "chicken=minecraft:chicken,minecraft:cooked_chicken", "mutton=minecraft:mutton,minecraft:cooked_mutton",
                        "rabbit=minecraft:rabbit,minecraft:cooked_rabbit,minecraft:rabbit_stew",
                        "fish=minecraft:cod,minecraft:cooked_cod,minecraft:salmon,minecraft:cooked_salmon,minecraft:tropical_fish",
                        "potato=minecraft:potato,minecraft:baked_potato", "carrot=minecraft:carrot,minecraft:golden_carrot",
                        "apple=minecraft:apple", "golden_apple=minecraft:golden_apple,minecraft:enchanted_golden_apple",
                        "stew=minecraft:mushroom_stew,minecraft:suspicious_stew,minecraft:beetroot_soup",
                        "beetroot=minecraft:beetroot", "bread=minecraft:bread", "cookie=minecraft:cookie", "pie=minecraft:pumpkin_pie",
                        "berries=minecraft:sweet_berries", "melon=minecraft:melon_slice", "kelp=minecraft:dried_kelp",
                        "honey=minecraft:honey_bottle", "chorus=minecraft:chorus_fruit"),
                "Food categories: name=item,item,#tag. Items in one category count once together. Foods not listed count",
                "as their own category (so modded foods work without configuration).");
        list("excludedFoods", ImmutableList.of("minecraft:rotten_flesh", "minecraft:spider_eye", "minecraft:poisonous_potato",
                        "minecraft:pufferfish"),
                "Foods that never count (ids or #tags).");
        pop();

        SPEC = B.build();
        B = null;
    }

    private static void moon(String id, String comment, boolean original) {
        push(id, comment);
        bool("enabled", true, "Include this moon in the forecast (applied when a world loads; restart to change).");
        if (original) bool("superVariantEnabled", true, "Keep Enhanced Celestials' Super variant of this moon in the forecast.");
        list("dimensions", ImmutableList.of(),
                "Dimension ids where this moon's add-on effects apply. Empty = every dimension with an Enhanced Celestials",
                "lunar context (by default only overworld-like dimensions have one).");
    }

    private static void push(String name, String... comment) {
        B.comment(comment).push(name);
        section = name;
    }

    private static void pop() {
        B.pop();
        section = "";
    }

    private static String key(String name) {
        return section.isEmpty() ? name : section + "." + name;
    }

    private static void bool(String name, boolean def, String... comment) {
        VALUES.put(key(name), B.comment(comment).define(name, def));
    }

    private static void dbl(String name, double def, double min, double max, String... comment) {
        VALUES.put(key(name), B.comment(comment).defineInRange(name, def, min, max));
    }

    private static void intv(String name, int def, int min, int max, String... comment) {
        VALUES.put(key(name), B.comment(comment).defineInRange(name, def, min, max));
    }

    private static void str(String name, String def, String... comment) {
        VALUES.put(key(name), B.comment(comment).define(name, def));
    }

    private static void list(String name, List<String> def, String... comment) {
        VALUES.put(key(name), B.comment(comment).defineList(name, new ArrayList<>(def), o -> o instanceof String));
    }
}
