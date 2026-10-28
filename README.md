# Enhanced Celestials: Sixteen Moons (`ecmoons`)

A Forge **1.16.5** add-on for **Enhanced Celestials 2.0.9**. It turns Enhanced Celestials' three lunar events into a sixteen-moon lineup:

| # | Moon | Theme | Event key |
|---|------|-------|-----------|
| 1 | Blood Moon | combat, rare drops | `enhancedcelestials-blood_moon` (original, extended) |
| 2 | Blue Moon | Luck, fishing, dungeon loot | `enhancedcelestials-blue_moon` (original, extended) |
| 3 | Harvest Moon | farming, food | `enhancedcelestials-harvest_moon` (original, extended) |
| 4 | Mist Moon | visibility, stealth | `ecmoons-mist_moon` |
| 5 | Frost Moon | exposure, preparation | `ecmoons-frost_moon` |
| 6 | Arcane Moon | enchanting, repairs | `ecmoons-arcane_moon` |
| 7 | Prospector's Moon | mining | `ecmoons-prospector_moon` |
| 8 | Wisdom Moon | experience | `ecmoons-wisdom_moon` |
| 9 | Quiet Moon | recovery | `ecmoons-quiet_moon` |
| 10 | Drift Moon | movement | `ecmoons-drift_moon` |
| 11 | Revealing Moon | an eye that reveals where players stand | `ecmoons-revealing_moon` |
| 12 | Thread Moon | team bonds (admin-assigned moon teams) | `ecmoons-thread_moon` |
| 13 | War Moon | a temporary equal-kit arena tournament | `ecmoons-war_moon` |
| 14 | Gluttony Moon | variety of food = temporary hearts | `ecmoons-gluttony_moon` |
| 15 | Afterimage Moon | Frame Step: a timed three-step dash (Moon Ability key) | `ecmoons-afterimage_moon` |
| 16 | Shell Moon | a temporary projectile barrier (Moon Ability key) | `ecmoons-shell_moon` |

Players can read about every moon in the **Lunar Field Guide**, a Patchouli book (§10), if Patchouli is installed.

The original Enhanced Celestials jar is **not modified**. The add-on depends on it and plugs into its systems.

---

## 1. Installation

**Required on the dedicated server and on every player's client:**

| File | Notes |
|------|-------|
| Forge 1.16.5 (36.2.x) | Built against 36.2.39. |
| `enhancedcelestials-2.0.9-1.16.5.jar` | Unchanged. Already part of Crazy Craft Updated. |
| `ecmoons-1.0.0-bNN.jar` | This add-on. The `bNN` suffix is the CI build number. |

Steps:
1. Stop the server and put `ecmoons-…jar` in `mods/`.
2. Put the same jar in every player's `mods/` folder. Clients need it for the moon textures, fog, Drift movement, the frost overlay and the Arcane tooltip. A client without it is refused at login with a version-mismatch message, so nobody can join without it by accident.
3. Start the server once. It creates `config/ecmoons-common.toml` and `config/ecmoons/blue_moon_bonus_loot.json`. It also adds the thirteen new event files to every world's Enhanced Celestials folder (see §3).
4. Optionally restart after editing the config.

The War Moon arena is a separate dimension, `ecmoons:war_arena`, defined in the add-on's built-in data pack (`data/ecmoons/dimension*/war_arena.json`). Nothing in your normal worlds is touched. Because of this dimension, opening a **singleplayer** world shows Mojang's "Worlds using Experimental Settings are not supported" prompt (click Proceed); dedicated servers and players joining them never see it. The War Moon also checks **Flan Warfare** (if installed) so it never starts during an active flag capture.

Optional mods it integrates with: **Patchouli** (the Lunar Field Guide, §10), **Lootr** (per-player chest loot gets Blue Moon bonus rolls) and **IDAS** (`idas:chests/pillager_fortress/pillager_basic` is a pre-configured Blue Moon table). Neither is required.

---

## 2. How the add-on integrates with Enhanced Celestials

Everything that decides **when** a moon happens stays inside Enhanced Celestials:
- the forecast and its persistence;
- the one-event-per-night rule and `minDaysBetweenLunarEvents`;
- the per-event chance, minimum nights and valid moon phases;
- rise/set chat notifications, sky-light colour, moon size, ambient sound;
- syncing the current event and forecast to clients.

The add-on decides **what** each moon does.

| Integration point | Why |
|---|---|
| Seven new codecs registered in `EnhancedCelestialsRegistry.LUNAR_EVENT`, plus default instances in `DEFAULT_EVENTS` | The new moons are real Enhanced Celestials events. They appear in `/ec lunarForecast` and `/ec setLunarEvent` and use EC's notifications and JSON config files. |
| `LunarContextMixin` (`handleEventConfigs`) | EC only writes default event files into an *empty* folder, so existing worlds would never get the new moons. This hook writes any missing file and then removes events disabled in the add-on config. EC itself then drops stale forecast entries. |
| `HarvestMoonMixin` (`onBlockItemDrop`) | Keeps EC's crop doubling as the **only** crop multiplier. It skips doubling when the broken crop is immature, which closes the plant-and-break loop that duplicates carrots and potatoes. |
| Quiet Moon via EC's `getSpawnMultiplierForMonsterCategory` | Uses EC's existing spawn-cap hook, so existing monsters and other spawn paths are untouched. |
| `WorldRendererMixin` (sky) | Binds the add-on moon texture right after EC binds and tints the moon, then draws the animated detail layer. Both injections are optional, so another sky mod can't crash the game. |
| `LootTableManagerMixin` | Appends Blue Moon pools the same way EC appends its own pools. Unlike Forge's `LootTableLoadEvent` in 1.16, this works for modded tables such as IDAS. |
| Global loot modifiers `ecmoons:blood_moon_rare_drops` and `ecmoons:prospector_bonus` | Add items without re-rolling or replacing the original loot. |
| `ServerPlayerEntityMixin` (`canHarmPlayer`) | War Moon only: two fighters can always hurt each other during the match, and nobody else involved can hurt or be hurt by players. Returns vanilla behaviour for everyone not in a tournament. |
| Data-pack dimension `ecmoons:war_arena` (end-style sky, fixed night, void biome, no Enhanced Celestials lunar context) | The War Moon arena never touches the main worlds and has no moon effects inside. |
| Persisted world data: `ecmoons_teams.dat`, `ecmoons_war_session.dat`, `ecmoons_war/<uuid>.dat` | Moon teams; the War Moon phase and involved players; one recovery record per player during a match. |
| Vanilla mixins: `PlayerEntity`, `FishingBobberEntity`, `RepairContainer`, `EnchantmentContainer`, `Block`, `AbstractBlockState`, `MobEntity`, `ServerWorld`; client: `EnchantmentScreen` | Exhaustion, fishing timers, anvil/enchanting costs, drop context, crop random ticks, spawn origin and footstep radius. Each targets one call site, checked against the 1.16.5 bytecode. |

**Gameplay decisions are made on the server.** Clients receive the server's settings and Enhanced Celestials' current event. They only render: fog, moon textures, the frost overlay and tooltips. The one exception is player movement (Drift Moon jump height and fall speed), because Minecraft 1.16 simulates player movement on the player's own client. Fall damage, which is what matters, is still decided on the server.

**Flan, Flan Warfare, PvP, raids and claims:** no moon reads or writes claim data, permissions, raid windows, capture progress or difficulty. Mist Moon changes only visibility and sound range. Quiet Moon lowers new natural spawns only, never via Peaceful.

---

## 3. Configuration

### 3.1 When moons happen: Enhanced Celestials event files (per world)

`config/enhancedcelestials/<namespace>/<dimension>/lunar/events/*.json`, for example `config/enhancedcelestials/minecraft/overworld/lunar/events/ecmoons-mist_moon.json`.

Each file holds `chance`, `minNumberOfNightsBetween`, `validMoonPhases` (0 = full … 4 = new), `blockSleeping`, `textComponents` (notifications) and `clientSettings` (sky-light colour, moon size, texture, sound). The global spacing is in `lunar-settings.json` (`minDaysBetweenLunarEvents`, default 5).

Default chances for the new moons are 0.02–0.04, the same range as the originals (Blood 0.07, Harvest 0.035, Blue 0.02). Ordinary nights stay the norm. To make a moon rarer or more common, edit its `chance` and run `/ec lunarForecast recompute`.

Afterimage and Shell were added with the lowest default chance (0.02 each, like War and Blue). Enhanced Celestials' global spacing (`minDaysBetweenLunarEvents`, default 5) still allows at most one special night per 5 days, so new moons mainly share the existing special nights rather than adding many more. If you want the overall rate to stay exactly as before, lower another moon's `chance` by the same amount (for example Mist and Quiet from 0.04 to 0.02) and run `/ec lunarForecast recompute`.

### 3.2 What moons do: `config/ecmoons-common.toml`

Every moon has:
- `enabled`: include it in the forecast. Applied when a world loads, so restart to change.
- `dimensions`: dimension ids where its add-on effects apply. Empty means every dimension that has an EC lunar context; EC only creates those for overworld-like dimensions.
- `superVariantEnabled` (Blood, Blue and Harvest only): keep EC's Super variant in the forecast.

`[general]`
- `superVariantsInheritCustomFeatures` (default `true`). Super variants always keep their own stronger Enhanced Celestials values (spawn cap ×4.5, Luck V, crop drops ×4, double moon size). When this is true, they also get the add-on features of their base moon **at the same strength, never doubled**. When false, they behave exactly as in Enhanced Celestials.
- `disabledEventKeys`: any extra EC event keys to remove.

Main values (defaults shown):

| Moon | Key | Default | Meaning |
|---|---|---|---|
| Blood | `rareDropMultiplier` | 1.25 | Multiplicative: 4% becomes 5%. |
| | `rareDropRules` | wither skull, zombie/husk/zombie-villager iron+carrot+potato, drowned gold, zombified piglin gold | `entity\|item[,item]\|baseChance\|perLooting`. Entity can be an id, `#tag` or `*`. |
| | `requirePlayerKill` | true | A real player (not a FakePlayer) must make the kill. |
| | `eligibleSpawnOrigins` | NATURAL, CHUNK_GENERATION, PATROL, JOCKEY, REINFORCEMENT, EVENT, STRUCTURE | **SPAWNER is excluded**, so spawner mobs get no bonus. |
| | `untrackedMobsEligible` | true | Mobs whose origin is unknown. |
| Blue | `fishingWaitMultiplier` | 0.70 | 30% shorter bite wait. |
| | `bonusLootFile` | `ecmoons/blue_moon_bonus_loot.json` | See 3.3. |
| Harvest | `growthMultiplier` | 2.0 | Extra random ticks for configured crops only. |
| | `growthBlocks` | `#minecraft:crops`, nether wart, cocoa, sweet berries, sugar cane, cactus, bamboo | Add modded crops by id or tag. |
| | `exhaustionMultiplier` | 0.75 | 25% less hunger. |
| | `requireMatureCrops` | true | See the integration table. |
| Mist | `fogStart` / `fogEnd` | 6 / 30 | Gives about 24–30 blocks of useful outdoor visibility. |
| | `fadeSeconds` | 10 | Fade in and out. |
| | `nameplateMaxDistance` | 22 | Other players' name tags are hidden beyond this distance. |
| | `footstepRangeMultiplier` | 0.5 | Footsteps heard at half distance. |
| Frost | `exposureDelaySeconds` | 20 | Seconds under open sky before the slowdown. |
| | `slowdown` | 0.15 | 15% slower. |
| | `warmthSeconds` | 45 | Warmth after eating; eating again refreshes the timer, it never stacks. |
| | `heatSourceRadius` | 4 | |
| | `heatSources` | campfires, fire, lava, magma, furnace, blast furnace, smoker | Lit-state blocks must be lit (`requireLitState`). |
| | `insulatingItems` | 4 leather pieces | Add modded fur/wool armour here. |
| | `delayBonusPerPieceSeconds` | 10 | Each insulating piece adds 10 s. |
| | `fullSetImmune` | true | Four insulating pieces prevent the slowdown. |
| Arcane | `levelCostMultiplier` | 0.75 | |
| | `minLevelCost` | 1 | |
| | `repairBonusMultiplier` | 1.25 | |
| | `affectEnchanting` / `affectAnvil` | true | |
| Prospector | `bonusChance` | 0.25 | |
| | `miningSpeedMultiplier` | 1.25 | |
| | `oreBonuses` | vanilla ores | `block=item` or `block=item*count`. |
| | `speedBlocks` | `#forge:ores`, `#forge:stone`, netherrack, basalt, blackstone, end stone | |
| | `legacyChunkPolicy` | ALLOW | See §5. |
| Wisdom | `mobXpMultiplier` / `oreXpMultiplier` | 2.0 | |
| | `xpOreBlocks` | vanilla XP ores | |
| | `eligibleSpawnOrigins` | NATURAL, CHUNK_GENERATION, PATROL, JOCKEY, REINFORCEMENT | |
| | `untrackedMobsEligible` | false | |
| Quiet | `monsterCapMultiplier` | 0.35 | |
| | `combatCooldownSeconds` | 12 | |
| | `minFoodLevel` | 18 | |
| | `regenIntervalTicks` | 40 | |
| | `regenAmount` | 1.0 | 1.0 = half a heart every 2 s on top of vanilla. |
| Drift | `jumpBoost` | 0.07 | About 1.6-block jumps; 2-block walls still stop players. |
| | `gravityMultiplier` | 0.6 | Applies while falling. |
| | `fallDamageMultiplier` | 0.5 | |
| | `jumpExhaustionMultiplier` | 0.5 | |
| Revealing | `scanIntervalSeconds` | 60 | One "The eye is searching…" warning per interval. |
| | `countdownSeconds` | 5 | Countdown between the warning and the scan. |
| | `markerSeconds` | 8 | How long the eye markers stay visible. |
| | `revealRadius` | 256 | Viewers only get markers within this horizontal distance. |
| | `includeCreative` | false | Spectators are never revealed. |
| | `protectedAreas` | (none) | `dimension\|x1,z1\|x2,z2`. Claims are **not** protected automatically. Tag `ecmoons_reveal_exempt` also hides a player. |
| | `blinkCloseTicks` / `blinkHoldTicks` | 6 / 4 | Eyelid timing. |
| | `idleBlinkMinSeconds` / `idleBlinkMaxSeconds` | 6 / 14 | Random gap between idle blinks. |
| Thread | `bondRange` | 12 | Blocks. |
| | `reductionPerTeammate` / `maxReduction` | 0.05 / 0.15 | 5% per bonded teammate, at most 15%. |
| | `fadeSeconds` | 3 | The bonus fades to zero over this time after separating. |
| | `requireLineOfSight` | true | A clear line between the two players' eyes. |
| | `excludedDamageTypes` | starve, drown, outOfWorld, inWall, cramming, fall, lava, inFire, onFire, hotFloor, magic, wither | Damage-source ids never reduced. |
| War | `warningSeconds` | 120 | Reminders at 1:00, 0:30 and 0:10. |
| | `minParticipants` / `maxParticipants` | 2 / 16 | |
| | `prepSeconds` / `matchSeconds` | 10 / 360 | Cell countdown; time limit before sudden death. |
| | `suddenDeathShrinkSeconds` | 8 | The safe square shrinks by 2 blocks this often (arena only, never the world border). |
| | `celebrationSeconds` | 10 | Winner announcement before everyone returns. |
| | `deferMinutes` / `maxDeferrals` | 2 / 5 | Postpone while a Flan Warfare capture runs; cancel after 5 postponements. |
| | `excludedPlayers` | (none) | Names or UUIDs; tag `ecmoons_war_exempt` does the same. |
| | `kit` | iron armour, shield, iron sword, bow, 16 arrows, 16 cooked beef | `slot=item` or `item*count`. |
| | `accessoryCapabilities` | `curios:inventory` | Equipment capabilities that are saved, emptied and restored. |
| | `ignoredCapabilities` | (none) | Item-holding capabilities that are not equipment. Any other item-holding capability blocks that player. |
| | `arenaFloorSize` / `arenaWallHeight` | 48 / 12 | Built-in arena. |
| | `arenaTemplate`, `arenaCells`, `arenaSpectatorPos` | empty | Replace the built-in arena with a structure template. |
| | `championMinutes` | 30 | Minutes of online time. |
| Afterimage | `step1Distance` / `step2Distance` / `step3Distance` | 2 / 3 / 4 | Blocks per step. |
| | `stepTicks` | 3 | Each step moves over this many ticks, stopping at the first collision. |
| | `inputWindowTicks` | 5 | Window after a step ends in which the next press counts. |
| | `maxSteerDegrees` | 45 | How far step 2 and 3 can turn from the previous one. |
| | `penaltyTicks` / `penaltySlowdown` | 10 / 0.4 | A too-early press: −40% speed for 0.5 s. |
| | `cooldownSeconds` | 8 | Starts when the sequence ends (completed, missed or broken). |
| | `finalWindowTicks` / `knockbackBonus` | 20 / 0.6 | After step 3, the next melee hit within 1 s gets +60% knockback, once. |
| | `afterimageTicks` | 12 | How long the visual afterimages last. |
| Shell | `chargeTicks` | 40 | Crouch and hold this long without moving. |
| | `radius` / `durationTicks` | 3.0 / 120 | |
| | `cooldownSeconds` | 20 | Starts when the shell ends. |
| | `moveTolerance` | 0.6 | Moving further than this from where it was raised collapses it. |
| | `durability` / `projectileCost` / `explosiveCost` | 100 / 10 / 30 | Fireballs, wither skulls and fireworks are explosive. |
| | `explosionReduction` | 0.75 | For players inside, applied once even where shells meet. |
| | `extraProjectileEntities` | (none) | Modded projectile entity ids that don't extend vanilla's projectile class. |
| | `respectProtection` | true | Only where the caster could place a block (see §5). |
| Gluttony | `maxBonusHearts` | 4 | |
| | `foodCategories` | 20 vanilla groups (beef, pork, fish, …) | `name=item,item,#tag`. Unlisted foods (including modded) count as their own category. |
| | `excludedFoods` | rotten flesh, spider eye, poisonous potato, pufferfish | |

The file reloads automatically when edited; `/ecmoons reload` forces a re-read and re-sends settings to all players. **Exceptions:** `enabled`, `superVariantEnabled` and `disabledEventKeys` need a restart, and Blue Moon loot needs `/reload`.

### 3.3 Blue Moon bonus loot: `config/ecmoons/blue_moon_bonus_loot.json`

```json
{
  "enabled": true,
  "maxBonusRolls": 2,
  "luckPerBonusRoll": 1.0,
  "tables": {
    "idas:chests/pillager_fortress/pillager_basic": "dungeon",
    "minecraft:chests/simple_dungeon": "dungeon"
  },
  "presets": { "dungeon": { "entries": [ ...ordinary loot-table entries... ] } }
}
```

For each listed table the original pools are kept unchanged, and `maxBonusRolls` extra pools are appended. Pool *k* rolls once only if **Blue Moon is active in that world AND the loot context's Luck ≥ k × luckPerBonusRoll**. Blue Moon gives Luck I (+1), so one bonus roll. The Super Blue Moon gives Luck V, which is capped at `maxBonusRolls` (2).

The default preset has an enchanted book, golden apple, name tag, saddle, XP bottles, a rare enchanted golden apple and an empty weight. There are no ores or ingots: ore bonuses belong to Prospector's Moon. Tables that don't exist on the server (for example IDAS not installed) are skipped with an info log line.

**Lootr check (verified in Lootr's 1.16 source, `LootrChestTileEntity.fillWithLoot`, unchanged since its first 1.16 release):**
- Lootr builds each player's loot context with `withLuck(player.getLuck())` and the player as `THIS_ENTITY`, so the bonus pools see that player's Luck.
- It generates an inventory only on that player's first opening and stores it per player UUID (`ChestData`). Reopening never rerolls, and a moon change never alters stored inventories.
- A different player's first opening generates their own inventory with their own Luck at that moment.
- Barrels, shulkers and chest minecarts use the same code.
- A table only benefits if whatever generates it passes Luck. Vanilla chests opened by a player do; hoppers and command-generated loot do not, so those get no bonus.

---

## 4. Administrator commands

All commands act on the dimension the command runs in. Use `/execute in <dim> run ecmoons …` for another one.

| Command | Permission | What it does |
|---|---|---|
| `/ecmoons list` | 2 | Every event in this dimension's lunar context: chance, minimum nights, phases, sleep, and whether add-on effects apply (including Super inheritance). |
| `/ecmoons active` | 2 | Current event, the add-on effects in force, Super flag, night flag, fade %, and the next 5 forecast entries. |
| `/ecmoons start <eventKey>` or `/ecmoons start <eventKey> now` | 3 | Puts the event at the head of tonight's forecast. If it's daytime, sets the time to dusk (13000), as `/ec setLunarEvent` does. |
| `/ecmoons start <eventKey> tonight` | 3 | Schedules it for tonight without changing the time. |
| `/ecmoons end` | 3 | Removes tonight's event from the forecast. EC returns to the ordinary moon on the next tick and sends its set notification; the add-on removes its modifiers and visuals. |
| `/ecmoons settings <moon\|general>` | 2 | Effective server values for that moon. |
| `/ecmoons inspect player <player>` | 2 | Frost exposure, warmth and insulation; Quiet combat timer; Drift landing grace; Luck; name-tag distance. |
| `/ecmoons inspect block <x y z>` | 2 | Placement record, legacy chunk flag, natural verdict, Prospector bonus mapping. |
| `/ecmoons inspect entity <target>` | 2 | Recorded spawn origin, plus Wisdom and Blood eligibility. |
| `/ecmoons inspect loot` | 2 | Which loot tables received Blue Moon bonus pools. |
| `/ecmoons reload` | 3 | Re-read `ecmoons-common.toml` and re-sync it to all players. |
| `/ecmoons reveal warn` | 2 | Revealing Moon: start the next search now (warning, countdown, eye shuts, scan). |
| `/ecmoons reveal scan` / `scan self` | 2 | Scan immediately in this dimension. `self` also shows you your own marker (testing). |
| `/ecmoons reveal blink` | 2 | Play one eyelid blink for you (the eye must be in your sky). |
| `/ecmoons reveal status` | 2 | Time to the next warning or scan. |
| `/ecmoons thread status [player]` | 2 | Moon team and current bond reduction. |
| `/ecmoons gluttony status [player]` | 2 | Counted food categories, bonus hearts, max health. |
| `/ecmoons gluttony reset <player>` | 2 | Clear Gluttony progress (hearts removed, health clamped). |
| `/ecmoons war status` | 2 | Phase, fighters, watchers, pending recovery records, arena dimension and Flan Warfare state. |
| `/ecmoons war start` / `start now` / `start solo` | 3 | Start a tournament with the full warning, without it, or in solo test mode (1 player is enough). Works without the moon. |
| `/ecmoons war cancel` | 3 | Emergency stop: everyone is restored at once, the arena removed. |
| `/ecmoons war records` | 2 | Pending recovery records (players who were offline when a match ended, or after a crash). |
| `/ecmoons war recover <player>` | 3 | Apply a pending record now (otherwise it is applied at the player's next login). |
| `/ecmoons war exclude <players>` / `include <players>` | 2 | Never enrol / enrol again (scoreboard tag `ecmoons_war_exempt`). |
| `/ecmoons war spectate <players> [true\|false]` | 2 | Watch from the gallery instead of fighting. |
| `/ecmoons war champion <player>` / `… clear` | 3 | Grant or remove the War Champion reward (testing). |
| `/ecmoons war arena build` / `arena clear` | 3 | Build the arena to look at it (`/execute in ecmoons:war_arena run tp @s 0 125 0`), then remove it. |
| `/moonteams create <teamId> <displayName>` | 2 | New moon team. |
| `/moonteams assign <player> <teamId>` | 2 | Put a player in a team (moves them out of any other; offline players by name work if the server knows them). |
| `/moonteams remove <player>` | 2 | Remove from their team. |
| `/moonteams list` / `info <teamId>` / `delete <teamId>` | 2 | |
| `/ecmoons ability status <player>` | 2 | Afterimage step/window/bonus and cooldown, Shell charge/active shell and cooldown. |
| `/ecmoons ability reset <players>` | 2 | Clear Moon Ability cooldowns and stop any running Frame Step or shell for those players. |
| `/ecmoons shell list` | 2 | Every active shell: owner, dimension, position, durability, time left. |
| `/ecmoons shell clear` | 2 | Collapse every active shell (cooldowns start as usual). |
| `/warmoon spectate` | 0 | Any player: toggle watching the next War Moon battle instead of fighting. |
| `/warmoon status` | 0 | Tournament phase and your remaining War Champion time. |

Start or stop any moon with `/ecmoons start ecmoons-revealing_moon now` (or `-thread_moon`, `-war_moon`, `-gluttony_moon`) and `/ecmoons end`.

Enhanced Celestials' own `/ec setLunarEvent <key>` and `/ec lunarForecast [recompute]` keep working and include the new moons.

---

## 5. Moon by moon: what is implemented, and the limits

**Blood Moon.** EC's ×3 hostile cap and blocked sleeping are unchanged.
- Rare drops use a second-chance roll: when none of a rule's items dropped, one is added with probability q = (m−1)p/(1−p), so the overall chance is exactly m·p.
- The base chance p must match the mob's loot table. The defaults are copied from the 1.16.5 tables, including Looting.
- Ordinary drops are never re-rolled or duplicated.
- Only death drops of hostile mobs killed by a real player count. Mobs from spawners never qualify; equipment drops are not loot-table drops and are not affected.

**Blue Moon.** EC's Luck effect is unchanged. New fishing wait times (lure, then bite) are scaled once when the game picks them, so Lure still works and nothing is applied twice. Bonus loot is described in §3.3.

**Harvest Moon.**
- EC's crop doubling is kept, except for immature crops.
- Growth: after a configured crop's normal random tick it gets one more, the same chance to grow again. Other blocks and the world's random tick speed are untouched.
- Hunger exhaustion is ×0.75.
- Right-click harvesting: mods that drop items through `Block.popResource` (the usual way) get EC's doubling once; those that spawn item entities directly do not. No harvesting path is doubled twice. **In-game check needed** for Crazy Craft's harvest mod.

**Mist Moon.**
- Fog: client-side linear fog fades to start 6 / end 30 blocks over 10 seconds. The fog colour is pulled towards grey. Water, lava and blindness fog are left alone, and the sky and HUD are not fogged.
- Name tags: the server lowers every player's Forge `nametag_distance` attribute to 22 during the moon, so vanilla renderers stop drawing distant tags. The client also hides tags beyond 22 blocks while the mist is thick.
- Footsteps: the server broadcasts sounds whose id ends in `.step` to half the radius. Attacks, explosions, block breaking and other sounds are unchanged.
- Graphics and shaders: the fog uses the vanilla fog pipeline (`RenderFogEvent`).
  - Vanilla and OptiFine *without* shaders should show it. OptiFine's "Fog: Off" setting removes it for that player, though their name tags and footsteps are still reduced by the server.
  - Shader packs (OptiFine shaders, Oculus) usually compute their own fog and may ignore it entirely.
  - This has **not** been tested with any shader pack.

**Frost Moon.**
- Exposure: the player's head is at or above the highest motion-blocking block. Glass, leaves and any roof count as shelter.
- After 20 s exposed, a dedicated attribute modifier gives −15% movement speed. It is not a potion and leaves other modifiers alone.
- A lit heat source within 4 blocks, shelter, or warmth from eating clears it. Each leather piece adds 10 s, and a full set prevents it.
- Checked every 10 ticks per player, and the heat scan only runs when the player is exposed.
- Visuals: a subtle frost border on the screen edges (max opacity 0.35, drawn beneath the HUD) and sparse white-ash particles outdoors. Blocks, water and crops never change.

**Arcane Moon.**
- Rounding is half up, minimum 1: discounted = max(min(orig, 1), round(orig × 0.75)).
  - Enchanting slots cost 1/2/2 levels instead of 1/2/3.
  - Anvil examples: 4→3, 10→8, 39→29.
- Enchanting: whether the discount applies is decided **once when the table is opened** and sent to the client in the container's data slot. The tooltip and the server charge read the same value, so a dawn transition can't cause a mismatch. The level *requirement* and lapis cost are unchanged.
- Anvil: material repairs restore 25% more per material, so fewer materials may be consumed; the item still needs its proper repair material. The unit count and cost are recomputed the way vanilla does, then discounted.
- "Too Expensive" is decided on the original cost, so the discount never unlocks a 40+ operation.
- Anvil costs live in the server-synced data slot, so the displayed cost is what's charged.
- Recipes handled by another mod's `AnvilUpdateEvent` are left alone, and modded enchanting tables that replace the vanilla container are not affected.

**Prospector's Moon.**
- 25% chance of +1 mapped resource from manually mined natural ores. Real players only: FakePlayers and machines are excluded, and Silk Touch disables it.
- The bonus is a flat extra item and does not interact with Fortune.
- +25% mining speed on natural stone and ores. Player-placed blocks break at normal speed. The server decides, and tells the client per block so its predicted break time matches.
- **How "natural" is decided** (a block ID alone is never treated as proof): a chunk capability records every position where a tracked block was placed by an entity (players, Endermen, fake players), created by a fluid (cobblestone/stone generators), or moved by a piston. Records are saved with the chunk. Repeatedly placing and breaking an ore therefore never produces extra resources. Limits:
  - **Pre-existing chunks** have no history. They are flagged *legacy*; `legacyChunkPolicy = DENY` turns bonuses off there, at the cost of also disabling them for genuinely natural ore in explored terrain.
  - **Modded placement** that calls `setBlock` without firing Forge's placement event (some builders' wands, schematic printers, structure tools) is not recorded.
  - **Moved blocks:** piston moves are tracked. Create contraptions, frames and similar movers are not.

**Wisdom Moon.**
- Doubles XP once, where it is created: mob XP on death, for hostile monsters killed by a real player, and ore XP on break, for natural configured ores mined manually.
- Picking up orbs is never multiplied.
- XP bottles, smelting, breeding, fishing and trading are untouched.
- Spawn origins are recorded in each mob's NBT. Forge's `CheckSpawn` marks spawner mobs as `SPAWNER`, and a hook at the start of `MobEntity.finalizeSpawn` records the vanilla `SpawnReason` for everything else. The first recorded origin wins.
- Qualifying by default: NATURAL, CHUNK_GENERATION, PATROL, JOCKEY, REINFORCEMENT. Untracked mobs don't qualify.

**Quiet Moon.**
- New natural hostile spawns are cut to 35% of the cap; existing monsters stay.
- Players with at least 18 food who haven't dealt or taken entity-sourced damage for 12 s heal half a heart every 2 s on top of vanilla regeneration.
- No difficulty change, and PvP, claims and raids are untouched.

**Drift Moon.**
- Jumps get +0.07 velocity on the player's client (stacks additively with Jump Boost).
- While falling, gravity is ×0.6 through Forge's `entity_gravity` attribute with the add-on's own modifier. It stacks multiplicatively with Slow Falling.
- Server-side: fall damage ×0.5 and jump exhaustion ×0.5.
- Sprint speed, attack speed and projectiles are untouched.
- Transitions: a player still airborne when the moon ends keeps the fall-damage reduction until landing. The gravity modifier is removed on landing or as soon as the moon ends.

**Revealing Moon.** The server owns the timing; clients only animate.
- Every `scanIntervalSeconds` (60) every player in that dimension gets "The eye is searching…" in chat, then a 5-second action-bar countdown. The eye on the moon closes so it is shut at the moment of the scan, then reopens.
- At the scan the server records where every eligible player is. Each viewer receives spectral eye markers for the players within `revealRadius` (256) of them, never their own. Markers show for 8 s, are drawn through terrain, and carry an approximate distance and compass direction ("~120 m NE"). No coordinates are ever printed in chat.
- Markers are placed at the player's position, lifted to the surface if the player is below it: an underground player only gives away their horizontal position. A roof does not protect.
- Never revealed: spectators, creative players (unless `includeCreative`), players tagged `ecmoons_reveal_exempt`, players inside a configured protected area and War Moon participants. Claims are not automatically protected.
- The eye blinks on its own every 6–14 s (open → half → closed → reopening). The four frames are separate pre-drawn textures with the iris in the same place. Because it is driven by Enhanced Celestials' synced event, players who join mid-moon see the eye and its blinks straight away; they simply wait for the next server scan.

**Thread Moon.** Teams are only what admins set with `/moonteams`: stored by UUID in `world/data/ecmoons_teams.dat`, one team per player. Nothing is inferred from chat colours, name prefixes, scoreboard teams or claim trust.
- Every half second, players of the same team in the same dimension within 12 blocks, with a clear line of sight between their eyes, are bonded.
- Each bonded teammate gives 5% damage reduction, capped at 15%. When teammates separate the bonus fades to zero over 3 s.
- The reduction is applied once per damage event (`LivingHurtEvent`), on the damaged player only. No damage is moved to teammates.
- Never bonded: spectators, dead players, players without a team, War Moon participants. Pets and other mobs are not players and never take part. Excluded damage types (starvation, drowning, the void, suffocation, fall, fire/lava, magic, wither by default) are never reduced.
- Team members (and only they) receive the links and see rose-coloured thread particles between bonded teammates. A small "Thread bond -10%" indicator appears next to the hotbar.

**War Moon.** A last-player-standing tournament in the separate `ecmoons:war_arena` dimension. The main world's PvP, raid windows and claims are never changed.
- **Warning:** when the War Moon rises (once per War Moon night) everyone sees "WAR MOON APPROACHES — Arena battle begins in 2:00. Your equipment will be stored safely. Everyone fights with the same loadout.", with reminders at 1:00, 0:30 and 0:10. If the moon sets during the warning, it is cancelled.
- **Flan Warfare:** if a flag capture is running when the warning ends, the battle is postponed by 2 minutes (up to 5 times, then cancelled for the night). Raids are never interrupted.
- **Enrolment:** every eligible online player (alive, not spectator/creative, not excluded) is enrolled, up to 16, chosen at random if more. Players who opted to spectate go to the gallery. Too few players cancels the tournament.
- **Safety order for each player:** (1) inventory, armour, offhand, accessory capabilities, XP, health, food, air, effects, game mode, flight and position are written to `world/data/ecmoons_war/<uuid>.dat` with an atomic write, (2) the file is read back and compared, and only then (3) the player is stripped, given the kit and moved. If any step fails, or the player has items in an equipment system the add-on does not know (an item-holding capability not in `accessoryCapabilities`), that player is **not enrolled** and is told why. Restoring always replaces the inventory with the saved one, so applying a record twice cannot duplicate anything.
- **Arena:** a fresh copy is built for each tournament and removed afterwards: a 48×48 floor with sea-lantern lighting, 12-block walls, 16 separate 3×3 starting cells with iron-bar gates, symmetric pillars and low walls, and a sealed viewing gallery above with an invisible barrier floor (nothing blocks the view down). A structure template can replace it (`arenaTemplate`).
- **Preparation:** 10-second countdown in the cells; nobody can attack. Then all gates open together.
- **Rules during the match:** adventure mode, no flight, all effects removed (including Gluttony hearts and the Champion's Haste). Only fighters can hurt fighters, and only in the arena. Breaking, placing, explosions, buckets, block interaction, item tossing (the item stays in your inventory), ender pearls/chorus fruit, mobs, summons and dropped items are blocked. Teleports out are undone within half a second.
- **Time limit:** after 6 minutes, sudden death: a red particle line marks a safe square that shrinks by 2 blocks every 8 s; outside it you take damage. If sudden death lasts 3 minutes there is no winner.
- **Knockouts instead of deaths:** lethal damage is cancelled; the player is moved to the gallery with full health and an empty inventory. No death, no drops, no graves, no XP loss. Spectators cannot be hurt and cannot hurt anyone.
- **Winner:** "WAR CHAMPION: <name> of <moon team>!" The winner gets **War Champion**: Haste I and a gold/crimson particle aura for 30 minutes of online time (the timer only runs while they are online and not in a match). Winning again resets it to 30 minutes; it never stacks. `/warmoon status` shows the remaining time.
- **Return:** after 10 s everyone is restored and sent back to where they were. If that spot is no longer safe, the nearest safe spot above it is used, otherwise world spawn.
- **Disconnects, crashes and restarts:** a fighter who logs out forfeits; their record stays on disk and is applied the next time they log in, wherever they appear. After a crash or restart the interrupted tournament is closed, the arena cleared, and every player is restored at their next login. `/ecmoons war records` lists pending records.

**Gluttony Moon.** (Earlier drafts called it Feast Moon: an existing `ecmoons-feast_moon.json` event file is converted to `ecmoons-gluttony_moon.json` automatically, keeping its chance and phases.)
- Each different food category actually eaten (`LivingEntityUseItemEvent.Finish`, so only completed eating counts) gives +1 max heart, up to 4. Raw and cooked versions of the same food are one category. Repeats don't count, and the action bar says so ("Gluttony: beef already counted (2/4 bonus hearts)").
- New hearts are empty: there is no instant heal.
- Excluded foods never count. Foods not in any category (including modded foods) count as their own category.
- The hearts are one event-owned max-health modifier, never saved into the player file. Progress is stored per night, so relogging during the same Gluttony night keeps it. It resets on death.
- When the moon ends, when you leave its dimension or enter a War Moon match, the hearts are removed and health is clamped to the new maximum.

**The Moon Ability key** (default **V**, rebindable under Controls → Enhanced Celestials Moons). One key for every moon ability; it does nothing on other nights ("No moon ability tonight"). The client only sends key down/up and the direction the movement keys point; the server checks the moon, timing, distances, collisions, cooldowns and permissions, and sends back what the small indicator next to the crosshair shows. Abilities are refused in the War Moon arena dimension and for anyone taking part in a War Moon tournament, and are cleaned up on death, logout, dimension change, moon end and server stop.

**Afterimage Moon — Frame Step.**
- Press: step 1 moves 2 blocks over 3 ticks in the movement-key direction (or the look direction). When the step ends, a 5-tick window opens: a press in it does step 2 (3 blocks), then step 3 (4 blocks). Steps 2 and 3 may turn at most 45° from the previous step.
- A press while a step is still moving or before the window is a mistimed input: the sequence breaks, −40% speed for 10 ticks, cooldown starts. Missing the window just ends the sequence.
- After step 3 the next direct melee hit within 20 ticks gets +60% knockback (once). Projectiles never qualify. Damage is unchanged.
- Movement is a server-side teleport in four sub-steps per tick, each checked with the collision box and the world border, so a step stops before walls, closed doors, ceilings and barriers and never passes through blocks. No dashing while riding, flying, gliding or swimming. No invulnerability; fall damage is unchanged.
- Afterimages are client-side drawings of the player's model (no entities), sent to the player and everyone tracking them.
- **Limit:** the dash itself doesn't ask claim mods whether you may *enter* a claim; it moves you the way walking would (2–4 blocks, never through walls). Claim mods that block walking into a claim by teleporting players back still apply.

**Shell Moon — Shell.**
- Crouch and hold the key: after 2 s (`chargeTicks`) without moving more than 0.6 blocks, a sphere of radius 3 centred on the player's chest appears for up to 6 s. Releasing the key, standing up, moving, running out of durability, the moon ending, dying, logging out or changing dimension collapses it. Cooldown 20 s after it ends.
- **Every projectile entity** (anything extending vanilla's `ProjectileEntity`, which covers arrows, tridents, snowballs, eggs, ender pearls, fishing hooks, splash/lingering potions, XP bottles, fireballs, wither skulls, firework rockets, llama spit, shulker bullets, and modded projectiles built on it) is stopped at the boundary in **both** directions. At the start of each world tick, before projectiles move, each projectile's straight path for that tick is tested against the sphere, so very fast projectiles that would jump across the boundary are caught too. A side-change check after the tick and a `ProjectileImpactEvent` guard catch anything that moved another way.
- Stopped projectiles are removed without hitting: no splash, no teleport, no explosion, no fire. Arrows and tridents that could have been picked up drop as exactly one item at the boundary on the side they came from; creative-only and non-pickup arrows drop nothing (as vanilla).
- Durability 100: each projectile costs 10, explosive projectiles 30. The projectile that empties it is still stopped, then the shell collapses.
- Players inside take 75% less explosion damage (once, even if shells overlap — overlapping shells are refused anyway). Melee, walking in and out, blocks and other damage are unaffected.
- **Protection:** before raising a shell, the add-on asks the protection mods whether the player could place a block there, by posting Forge's own block-place event (`respectProtection`). Flan, World Protector/YAWP and other mods that protect against block placement refuse it there. This is a synthetic event: a protection mod that only checks real item use would not see it.
- **Limits:** projectiles that are not entities (hitscan guns, beams, some modded weapons that damage directly) can't be stopped. Modded projectile entities that don't extend `ProjectileEntity` need their id in `extraProjectileEntities`.

---

## 6. Moon designs

All fourteen designs come from the reference sheets. They are packaged in the jar under `assets/ecmoons/textures/environment/`.
- `<moon>_moon_phases.png` is 512×256, the **same 4×2 phase layout as vanilla `moon_phases.png`** (phase *p* at column *p*%4, row *p*/4).
  - Each moon is 32×32 pixel art centred in a 128×128 cell, the same proportion as vanilla's 8-pixel moon in a 32-pixel cell. A halo fades out before the cell edge.
  - Phases shade the surface like vanilla: waning from the right, waxing from the left, with a faint rim at new moon.
  - The comparison sheet's labels, stars and background are not in any texture.
- `<moon>_moon_details.png` is an additive animated detail layer on the same quad. Quiet Moon and Thread Moon have none by design.
- Revealing Moon has four eye frames: `revealing_moon_phases.png` (open) and `revealing_moon_blink1/2/3.png` (partly closed, nearly closed, shut), all with the iris in the same position, plus glowing veins on the open eye.

| Static (always) | Animated (client option `animateMoonDetails`) |
|---|---|
| Surface, craters, colours, phases, halo, Frost's ice spikes, Thread's loose thread, Gluttony's sesame seeds | Revealing eyelid blinks and vein pulse; War gouge and ember glow; Blood fissure glow pulse; Blue, Harvest and Prospector glints and Frost cracks twinkling; Arcane rune pulse; Wisdom constellation twinkle; Mist wisps drifting sideways; Drift fragments bobbing |

Which design to draw comes from Enhanced Celestials' current event, which EC re-sends when a player joins or changes dimension. So players who join during an event see the right moon. Super variants use their base moon's design at EC's larger size.

Client options in `config/ecmoons-client.toml`: `useAddonMoonTextures`, `renderMoonDetails`, `animateMoonDetails`, `frostOverlay`, `frostOverlayOpacity`, `frostParticles`.

The source art pipeline is in `tools/art/`: `find_boxes.py` and `sample2.py` sample each moon from the reference, and `build_textures.py` builds the sheets.

---

## 7. Atmosphere (how each moon makes the world look)

Every moon also changes the night around it: sky and horizon colour, coloured moonlight on the landscape, distance haze, a moon halo, one sky animation, sparse ambient particles and a few quiet sounds. It is purely visual, rendered on each player's own client (**the same add-on jar on every client — nothing extra to install**; a vanilla client could not show any of this). Gameplay, scheduling and rewards are unchanged, and turning any of it off never changes a moon's mechanics.

**Which moon is shown** always comes from Enhanced Celestials' synchronised current event (or the War Moon arena dimension, or an admin preview). Changes fade over 8 seconds both ways: starting, ending, cancelling, changing dimension, reconnecting or dying all fade back to the untouched night, so no colour is ever left behind. Super variants use their base moon's profile, slightly stronger.

**What Enhanced Celestials already does** (and is kept): the moon texture/size/colour per event, a sky-light colour per event (its `skyLightColor` in the event JSON) and an optional soundtrack. The atmosphere module adds everything else on top. It does not replace the sky renderer: it adds layers inside the vanilla sky pass and modifies values (sky colour, fog colour, light map) after vanilla and Enhanced Celestials have computed them.

| Moon | Sky / light | Signature behaviour |
|---|---|---|
| Blood | dark crimson sky, red haze, slightly darker terrain | faint slow red wisps low in the sky and near the ground |
| Blue | rich cobalt sky, cool blue moonlight | brighter, blue-tinted stars; almost no particles |
| Harvest | warm amber sky and moonlight | pollen motes over grass, leaves, crops and flowers |
| Mist | desaturated slate sky, grey light | drifting ground wisps (the 30-block fog is the moon's existing mechanic) |
| Frost | pale cyan-grey sky, silver light | slowly falling ice crystals, intermittent cold breath, shimmering frost halo |
| Arcane | deep violet sky | irregular magical light ribbons overhead, runes that appear and dissolve, sparkles near enchanting tables and bookshelves |
| Prospector's | dark copper/umber sky, metallic amber light | mineral dust, short glints on ore faces that are exposed and in your direct line of sight (never through walls — not an x-ray) |
| Wisdom | clear midnight-indigo sky, silver light | many bright stars; constellation lines that slowly draw in, hold, fade and return at long intervals |
| Quiet | muted sage sky, slightly desaturated | stillness: almost no particles; ambient and weather sounds gently lowered |
| Drift | soft lavender sky | thin cloud streaks high up; luminous motes carried upwards and sideways that lift when you jump |
| Revealing | ash-grey desaturated sky | the eye: idle blinks and a wandering pupil; during the warning the lids narrow, the iris brightens and an amber halo builds; at the real scan it opens fully, a pale-gold ring sweeps across the sky with a brief light pulse; then it slowly closes and reopens |
| Thread | rose-grey sky | thin glowing strands across the sky; thin threads between bonded teammates (team only) |
| War | dark iron-grey sky, cold light | smoke bands with distant orange flashes, rising embers; during the battle warning everything darkens and the flashes speed up, one pulse at the summon; stronger inside the arena (visibility for combat kept) |
| Gluttony | burgundy sky, cream moonlight | warm golden motes, steam above lit furnaces/smokers/campfires, a honey-gold burst when someone eats a new food (stronger ring for a new heart) |

**Indoors and underground:** the colour grading only affects sky light, so caves, interiors and torch-lit rooms keep their normal colour; haze is scaled by how much sky you can see; sky particles only spawn under open sky. Particles are depth-tested and never draw through blocks.

**Performance:** client-side only (the server just sends small messages for real gameplay moments: War phases and Gluttony foods). Particles live in one fixed pool with a shared cap per player: LOW 8, MEDIUM 20, HIGH 35 (configurable). Block-based effects (pollen, ore glints, sparkles, steam) use a few random samples per tick near the player — never area scans. No entities, no shaders, no per-frame allocation in the particle system.

**Settings.**
- Server, `config/ecmoons-common.toml`, synced to every client: `[atmosphere]` master switch, `transitionSeconds` (8), `masterIntensity`, switches for sky tint, fog, light tint, particles, sky animations and sounds, `respectDimensions`, particle caps and range. One `[atmosphere_<moon>]` section per moon with `enabled`, colours (`skyColor`, `horizonColor`, `lightColor`, `glowColor`, `accentColor`), `intensity`, `tintStrength`, `fogStrength`, `brightness`, `desaturate`, `moonGlow`, `particleDensity`, `animationIntensity`, `soundVolume`, `particles`, `skyAnimation`, plus moon-specific switches (e.g. `frost.coldBreath`, `prospector.oreGlints`, `thread.teamLines`, `war.arenaBoost`, `gluttony.steam`, `quiet.ambientSoundReduction`).
- Each player, `config/ecmoons-client.toml` `[atmosphere]`: `quality` = OFF / LOW / MEDIUM (default) / HIGH, and `soundVolume`. OFF removes all atmosphere rendering; the moon textures, warnings, reveal markers and the Thread HUD stay.

**Previewing (admins):**
- `/ecmoons atmosphere preview <moon> [players]` shows a moon's atmosphere (and its moon texture) to you or the named players only. Visual only: the real event, its gameplay and the forecast don't change. Use it at night (`/time set 18000`). `/ecmoons atmosphere preview off` ends it.
- `/ecmoons atmosphere demo <reveal|war|feast> [players]` plays the Revealing scan sequence, the War build-up and summon pulse, or the Gluttony burst, without scanning, summoning or counting anything.

**Limits.** Real desaturation of block textures would need a shader, so "desaturated" moons grey the light and sky instead. War drums are not included (vanilla has no drum sound; a distant low rumble is used). OptiFine shader packs replace the sky, fog and light-map pipeline, so with shaders on, expect the moon textures and particles to remain but sky/fog/light colours to depend on the shader pack; set `quality` LOW or OFF if a shader pack conflicts. Mods that completely replace the vanilla sky renderer will skip the sky layers (the hooks are optional and never crash).

---

## 8. Building

Pushing to `main` builds the jar on GitHub Actions (Java 8, ForgeGradle 5, official mappings) and boots a dedicated test server with the self-test.

To build locally: `./gradlew build` produces `build/libs/ecmoons-<version>.jar`. `libs/enhancedcelestials-2.0.9-1.16.5.jar` is the unmodified LGPL-3.0 Enhanced Celestials release, used only as a compile and dev-run dependency; it is not bundled into the add-on.

---

## 9. Tests

Every push runs two automated suites:
- a **dedicated server** self test: 273 checks;
- a **real Minecraft client** test: 178 checks (including the atmosphere of all 14 moons). It takes screenshots of all fourteen moons in the sky, the Revealing eye blinking and a reveal marker, the Thread HUD, Gluttony hearts and a solo War Moon match; it measures Mist fog, the Frost overlay, Drift movement and the Arcane tooltip, and runs the ore, XP, rare-drop, Gluttony and War Moon save/strip/restore rules with a real player.

See [TESTING.md](TESTING.md) for exactly what is verified and what still needs checking with the full modpack. The latest screenshots are on the `ci-client` branch.

---

## 10. Lunar Field Guide (Patchouli)

With **Patchouli** installed (it is part of Crazy Craft Updated), the add-on ships an in-game book, the **Lunar Field Guide**: a picture of every moon as it appears in the sky, what it does, the default numbers and the limits and counterplay, plus chapters on the Moon Ability key, Frame Step and Shell. Patchouli is optional: without it the book and its recipe are simply not loaded.

- Craft it: **book + clock** (shapeless).
- Or give it: `/give @p patchouli:guide_book{"patchouli:book":"ecmoons:field_guide"}`
- The text uses the default config values. If you change them, edit `tools/book/build_book.py` and rebuild the jar (Patchouli for 1.16 reads book pages from the mod jar itself, so a resource pack can't override them).
