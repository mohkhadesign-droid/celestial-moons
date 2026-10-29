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
        dbl("activeScanSeconds", 3.0, 0.0, 60.0,
                "The last part of each countdown during which the eye is fully open and actively scanning (Binding Gaze",
                "works only then). It is carved out of countdownSeconds, so the cadence and the moment of completion are",
                "unchanged; it is capped at countdownSeconds - 1 so the eye always has at least a second to open.");
        intv("revealSeconds", 0, 0, 120,
                "Each revealed player's own reveal timer (True Exposure, Read Your Enemy, amber edges and colour loss).",
                "0 = the same as markerSeconds.");
        dbl("rippleSeconds", 3.0, 0.5, 10.0, "How long the single golden sky ripple takes to cross the sky after each scan completion.");
        pop();

        push("revealing_binding", "Revealing Moon - Binding Gaze: staring into the fully open eye roots you in place.");
        bool("enabled", true, "Enable Binding Gaze.");
        dbl("coneDegrees", 5.0, 0.5, 30.0, "How close to the centre of the moon your view must be (degrees from its centre).");
        dbl("stareSeconds", 2.0, 0.25, 10.0, "Uninterrupted staring (with open sky towards the moon) needed to be bound. Looking away resets it.");
        str("message", "The eye holds you. Look away.", "Shown once (action bar) when a bind begins.");
        pop();

        push("revealing_exposure", "Revealing Moon - True Exposure: revealed players cannot hide with the Invisibility effect.");
        bool("enabled", true, "Enable True Exposure. The potion is never removed or shortened; invisibility resumes afterwards if it is still running.");
        pop();

        push("revealing_inspect", "Revealing Moon - Read Your Enemy: revealed players can read another player's health and effects.");
        bool("enabled", true, "Enable Read Your Enemy.");
        dbl("range", 32.0, 4.0, 128.0, "Maximum distance to the inspected player (blocks).");
        dbl("aimSeconds", 1.0, 0.1, 10.0, "Uninterrupted aim with line of sight needed before the panel appears.");
        intv("updatesPerSecond", 4, 1, 10, "How often an acquired target's information is refreshed.");
        intv("maxEffectsShown", 6, 1, 16, "Effects listed on the panel (longest first); the rest are shown as \"+N more\".");
        dbl("aimTolerance", 0.5, 0.0, 2.0, "Extra margin (blocks) around the target's hitbox for the server's aim check (network latency).");
        pop();

        push("revealing_investigate", "Revealing Moon - The World Notices You: nearby hostiles investigate where a revealed player stood.");
        bool("enabled", true, "Enable investigations.");
        dbl("radius", 24.0, 4.0, 64.0, "Loaded hostile mobs within this distance of the revealed player's position may investigate.");
        intv("maxPerPlayer", 6, 0, 32, "Most investigators per revealed player per scan.");
        intv("maxPerScan", 48, 0, 512, "Processing budget: most investigators assigned per scan in one dimension.");
        intv("maxPlayersPerScan", 32, 0, 512, "Most revealed players processed per scan in one dimension (in a fixed order).");
        dbl("seconds", 10.0, 1.0, 60.0, "An investigation that finds nobody ends after this long.");
        dbl("searchSeconds", 3.0, 0.0, 30.0, "Time spent looking around after reaching the old spot (within the limit above).");
        dbl("speed", 1.0, 0.3, 1.5, "Navigation speed multiplier (1.0 = the mob's normal walking speed).");
        bool("respectProtection", true,
                "Skip spots under spawn protection or where the revealed player could not build (claims, World Protector",
                "regions and other protection mods, asked through Forge's block-place event).");
        list("mobs", ImmutableList.of("minecraft:zombie", "minecraft:husk", "minecraft:zombie_villager", "minecraft:skeleton",
                        "minecraft:stray", "minecraft:wither_skeleton", "minecraft:spider", "minecraft:cave_spider",
                        "minecraft:creeper", "minecraft:witch", "minecraft:pillager", "minecraft:vindicator"),
                "Mob types that may investigate (entity ids). Only hostile, ground-walking, untamed mobs with no current target",
                "are ever used, even if listed. Add modded hostiles here; bosses, neutral mobs and special AI should stay out.");
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

        moon("afterimage", "Afterimage Moon - Frame Step: a timed three-step dash with afterimages (Moon Ability key).", false);
        dbl("step1Distance", 2.0, 0.5, 8.0, "Distance of the first step (blocks).");
        dbl("step2Distance", 3.0, 0.5, 8.0, "Distance of the second step.");
        dbl("step3Distance", 4.0, 0.5, 8.0, "Distance of the third step.");
        intv("stepTicks", 3, 1, 10, "Ticks each step takes (20 ticks = 1 second).");
        intv("inputWindowTicks", 5, 2, 20, "After a step ends, the next press must come within this many ticks (5 = 0.25 s).");
        dbl("maxSteerDegrees", 45.0, 0.0, 180.0, "How far the direction may turn between steps (it never turns during a step).");
        intv("cooldownSeconds", 8, 0, 600, "Cooldown, starting when the sequence ends or is interrupted.");
        intv("penaltyTicks", 10, 0, 100, "Mistimed press: ticks of reduced movement (10 = 0.5 s).");
        dbl("penaltySlowdown", 0.4, 0.0, 0.9, "Mistimed press: movement reduction (0.4 = 40% slower; never a full freeze).");
        intv("finalWindowTicks", 20, 0, 100, "After a successful third step, the next valid melee hit within this many ticks gets the knockback bonus.");
        dbl("knockbackBonus", 0.6, 0.0, 3.0, "Extra knockback for that hit (0.6 = +60%). Damage, reach and attack cooldown are unchanged.");
        intv("afterimageTicks", 12, 2, 60, "How long the visual afterimages last.");
        pop();

        moon("shell", "Shell Moon - deploy a temporary projectile barrier by crouching and holding the Moon Ability key.", false);
        intv("chargeTicks", 40, 5, 200, "How long to crouch and hold the key before the shell appears (40 = 2 s).");
        dbl("radius", 3.0, 1.0, 8.0, "Shell radius (blocks).");
        intv("durationTicks", 120, 20, 1200, "Maximum duration (120 = 6 s). Releasing the key, standing up or moving collapses it earlier.");
        intv("cooldownSeconds", 20, 0, 600, "Cooldown, starting when the shell ends.");
        dbl("moveTolerance", 0.6, 0.1, 3.0, "How far the caster may drift from the deployment position before the shell collapses.");
        intv("durability", 100, 1, 10000, "Shared durability. The hit that exhausts it is still stopped; then the shell collapses.");
        intv("projectileCost", 10, 0, 1000, "Durability used by an ordinary projectile.");
        intv("explosiveCost", 30, 0, 1000, "Durability used by an explosive projectile (fireballs, wither skulls, fireworks...).");
        dbl("explosionReduction", 0.75, 0.0, 1.0, "Explosion damage reduction for players inside an active shell (applied once, even where shells overlap).");
        list("extraProjectileEntities", ImmutableList.of(),
                "Entity ids or #tags that are also treated as projectiles (for modded attacks that are not ordinary projectile entities).");
        bool("respectProtection", true, "Only allow a shell where the caster could place a block (claims, protected areas).");
        pop();

        moon("wisp", "Wisp Moon - pilot a small scouting wisp and place up to three soul observers (Moon Ability key).", false);
        dbl("pilotSpeed", 0.35, 0.05, 1.5, "Wisp flight speed in blocks per tick (0.35 = 7 blocks/s).");
        dbl("pilotRange", 48.0, 4.0, 128.0, "The wisp can't go further than this from your body (blocks).");
        intv("maxPilotSeconds", 90, 5, 600, "Piloting ends after this long.");
        intv("pilotCooldownSeconds", 10, 0, 600, "Cooldown after piloting ends.");
        intv("maxObservers", 3, 1, 8, "Observers per player. At the limit, dismiss one before placing another.");
        dbl("placeReach", 4.5, 1.0, 8.0, "How far from the wisp an observer can be placed (on a block face the wisp looks at).");
        dbl("viewRange", 48.0, 4.0, 128.0, "Observers further than this from your body can't be viewed.");
        dbl("viewYawRange", 60.0, 0.0, 180.0, "While viewing, how far you can turn left/right from the observer's direction (degrees).");
        dbl("viewPitchRange", 45.0, 0.0, 90.0, "While viewing, how far you can look up/down from the observer's direction (degrees).");
        dbl("bodyMoveTolerance", 0.5, 0.1, 3.0, "If your body is moved further than this while piloting or viewing, you return to it.");
        bool("respectProtection", true, "Only allow observers where the owner could place a block (claims, protected areas).");
        pop();

        // ---- atmosphere (visual only; synced to clients, rendered by the client add-on) ----------------------------
        push("atmosphere", "Atmosphere: how the world looks while each moon is up. Purely visual and rendered on each player's",
                "client (clients need this add-on installed). Turning anything here off never changes a moon's gameplay.");
        bool("enabled", true, "Master switch for all atmosphere rendering.");
        intv("transitionSeconds", 8, 1, 60, "Fade between the normal night and a moon's atmosphere (both ways).");
        dbl("masterIntensity", 1.0, 0.0, 2.0, "Multiplies every moon's atmosphere intensity.");
        bool("enableSkyTint", true, "Sky and horizon colours.");
        bool("enableFog", true, "Coloured distance haze (Mist Moon's gameplay fog is separate and always applies).");
        bool("enableLightTint", true, "Environmental colour grading through the light map (outdoor light only; caves and torchlight keep their colour).");
        bool("enableParticles", true, "Ambient atmosphere particles (crystals, embers, motes, runes, steam...).");
        bool("enableSkyAnimations", true, "Sky layers: ribbons, constellations, cloud streaks, smoke bands, threads, scan ring, moon halos.");
        bool("enableAmbientSounds", true, "Occasional quiet ambient sounds (vanilla sounds only).");
        bool("respectDimensions", true, "Only show a moon's atmosphere where its add-on effects apply (the moon's 'dimensions' list).");
        intv("maxParticlesLow", 8, 0, 64, "Most atmosphere particles alive at once per player on LOW quality (shared by all effects).");
        intv("maxParticlesMedium", 20, 0, 64, "... on MEDIUM quality.");
        intv("maxParticlesHigh", 35, 0, 64, "... on HIGH quality.");
        dbl("particleRange", 24.0, 8.0, 64.0, "Particles further than this from the player are removed (distance culling).");
        pop();
        atmo("blood", "590d17", "7d2028", "a81722", "e63236", "e63236", 0.35, 0.25, 0.90, 0.0);
        pop();
        atmo("blue", "174b9b", "132c58", "8fc8ff", "236ce0", "8fc8ff", 0.35, 0.10, 1.05, 0.0);
        pop();
        atmo("harvest", "8a5a26", "9b542d", "ffe0a0", "ffc15a", "ffc15a", 0.35, 0.15, 1.04, 0.0);
        bool("pollen", true, "Floating pollen-like motes near open vegetation.");
        pop();
        atmo("mist", "343948", "999da8", "d1d3db", "d1d3db", "a4a8b3", 0.25, 0.15, 0.97, 0.35);
        pop();
        atmo("frost", "617f9c", "b6ccd6", "e9f7ff", "a7dbed", "ddf4ff", 0.30, 0.20, 1.02, 0.25);
        bool("coldBreath", true, "Intermittent cold-breath vapour in front of the player outdoors (MEDIUM and HIGH quality).");
        bool("moonHalo", true, "Shimmering frost halo around the moon.");
        pop();
        atmo("arcane", "24123f", "7023b8", "d8a5ff", "b346e5", "ec77ff", 0.30, 0.10, 1.0, 0.0);
        bool("runes", true, "Rune symbols that briefly appear and dissolve.");
        bool("magicBlockSparkles", true, "Tiny sparkles near enchanting tables and bookshelves (bounded sampling, no area scans).");
        pop();
        atmo("prospector", "30221f", "8c512e", "d9a064", "b86632", "e7b46d", 0.30, 0.20, 0.98, 0.0);
        bool("oreGlints", true, "Short sparkles on ore faces that are exposed to air and in the player's direct line of sight (never through walls).");
        pop();
        atmo("wisdom", "0c1433", "232c67", "e0e8ff", "cfd9ff", "cfd9ff", 0.20, 0.0, 1.06, 0.0);
        bool("constellations", true, "Constellation lines that slowly appear, hold, fade and return at long intervals.");
        pop();
        atmo("quiet", "263c3a", "567b70", "d5f0e2", "91bdaa", "91bdaa", 0.25, 0.0, 1.0, 0.30);
        dbl("ambientSoundReduction", 0.6, 0.0, 1.0, "Quieter ambient and weather sounds (0 = unchanged, 1 = silent). Players, mobs, combat, blocks and UI are never touched.");
        pop();
        atmo("drift", "45446d", "8d86cb", "d4caff", "b0a1e8", "e4dffc", 0.25, 0.15, 1.02, 0.0);
        bool("reactToJumps", true, "Nearby drift motes are lifted when the player jumps.");
        pop();
        atmo("revealing", "29282d", "77716e", "e8dcc2", "d9ab54", "d9ab54", 0.30, 0.20, 0.96, 0.30);
        bool("scanRing", true, "One thin golden ripple crosses the whole sky at each scan completion, with a brief light pulse.");
        pop();
        atmo("thread", "393043", "a66e91", "e7bfd4", "c88ba7", "f1c8da", 0.30, 0.15, 1.0, 0.0);
        bool("skyStrands", true, "Thin glowing strands across the sky.");
        bool("teamLines", true, "Thin threads between bonded teammates (only members of that team see them).");
        pop();
        atmo("war", "30303a", "53545d", "b5adb0", "e76a38", "e76a38", 0.30, 0.30, 0.88, 0.20);
        dbl("arenaBoost", 1.3, 1.0, 2.0, "Atmosphere strength inside the War Moon arena (visibility for combat is always kept).");
        bool("preBattleBuildup", true, "Turbulence and orange flashes build up during the battle warning, with one pulse when players are summoned.");
        pop();
        atmo("afterimage", "2a2540", "6f6699", "d8ccf5", "c8b8f0", "e0d8ff", 0.25, 0.10, 1.0, 0.15);
        pop();
        atmo("shell", "26302a", "7d8a72", "e6eedc", "bfe3cf", "d8e8d0", 0.25, 0.10, 1.0, 0.1);
        atmo("wisp", "141e30", "3a5878", "b8e4ff", "8fe0ff", "c8f4ff", 0.30, 0.12, 0.95, 0.15);
        pop();
        atmo("gluttony", "30242d", "742f42", "f3d6a0", "e8b65f", "e8b65f", 0.30, 0.15, 1.0, 0.0);
        bool("steam", true, "Sparse steam above lit furnaces, smokers and campfires near the player (bounded sampling).");
        bool("foodBursts", true, "Honey-gold burst when a player eats a new food (stronger ring for a new heart).");
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

    /** One moon's atmosphere profile: [atmosphere_<moon>]. Leaves the section open for moon-specific extras. */
    private static void atmo(String moon, String sky, String horizon, String light, String glow, String accent,
                             double tint, double fog, double brightness, double desaturate) {
        push("atmosphere_" + moon, "Atmosphere profile for the " + moon + " moon (colours are hex RRGGBB).");
        bool("enabled", true, "Show this moon's atmosphere (the moon itself and its gameplay are unaffected).");
        str("skyColor", sky, "Night sky colour.");
        str("horizonColor", horizon, "Horizon / distance haze colour.");
        str("lightColor", light, "Moonlight colour used for environmental tinting.");
        str("glowColor", glow, "Moon halo / glow colour.");
        str("accentColor", accent, "Particle and sky-animation accent colour.");
        dbl("intensity", 0.65, 0.0, 1.0, "Overall strength of this atmosphere.");
        dbl("tintStrength", tint, 0.0, 1.0, "Environmental tint strength (light map, outdoor light only).");
        dbl("fogStrength", fog, 0.0, 1.0, "Coloured distance haze (0 = none).");
        dbl("brightness", brightness, 0.7, 1.3, "Outdoor light brightness multiplier at full strength.");
        dbl("desaturate", desaturate, 0.0, 1.0, "Pull outdoor light towards grey.");
        dbl("moonGlow", 0.5, 0.0, 1.0, "Halo strength around the moon.");
        dbl("particleDensity", 1.0, 0.0, 2.0, "Spawn rate multiplier for this moon's particles (always within the shared cap).");
        dbl("animationIntensity", 1.0, 0.0, 2.0, "Strength / speed of this moon's sky animation.");
        dbl("soundVolume", 0.35, 0.0, 1.0, "Volume of this moon's occasional ambient sounds (0 = none).");
        bool("particles", true, "This moon's particles.");
        bool("skyAnimation", true, "This moon's sky animation.");
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
