# Enhanced Celestials: Ten Moons (`ecmoons`)

A Forge **1.16.5** add-on for **Enhanced Celestials 2.0.9**. It turns Enhanced Celestials' three lunar events into a ten-moon lineup:

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
3. Start the server once. It creates `config/ecmoons-common.toml` and `config/ecmoons/blue_moon_bonus_loot.json`. It also adds the seven new event files to every world's Enhanced Celestials folder (see §3).
4. Optionally restart after editing the config.

Optional mods it integrates with: **Lootr** (per-player chest loot gets Blue Moon bonus rolls) and **IDAS** (`idas:chests/pillager_fortress/pillager_basic` is a pre-configured Blue Moon table). Neither is required.

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
| Vanilla mixins: `PlayerEntity`, `FishingBobberEntity`, `RepairContainer`, `EnchantmentContainer`, `Block`, `AbstractBlockState`, `MobEntity`, `ServerWorld`; client: `EnchantmentScreen` | Exhaustion, fishing timers, anvil/enchanting costs, drop context, crop random ticks, spawn origin and footstep radius. Each targets one call site, checked against the 1.16.5 bytecode. |

**Gameplay decisions are made on the server.** Clients receive the server's settings and Enhanced Celestials' current event. They only render: fog, moon textures, the frost overlay and tooltips. The one exception is player movement (Drift Moon jump height and fall speed), because Minecraft 1.16 simulates player movement on the player's own client. Fall damage, which is what matters, is still decided on the server.

**Flan, Flan Warfare, PvP, raids and claims:** no moon reads or writes claim data, permissions, raid windows, capture progress or difficulty. Mist Moon changes only visibility and sound range. Quiet Moon lowers new natural spawns only, never via Peaceful.

---

## 3. Configuration

### 3.1 When moons happen: Enhanced Celestials event files (per world)

`config/enhancedcelestials/<namespace>/<dimension>/lunar/events/*.json`, for example `config/enhancedcelestials/minecraft/overworld/lunar/events/ecmoons-mist_moon.json`.

Each file holds `chance`, `minNumberOfNightsBetween`, `validMoonPhases` (0 = full … 4 = new), `blockSleeping`, `textComponents` (notifications) and `clientSettings` (sky-light colour, moon size, texture, sound). The global spacing is in `lunar-settings.json` (`minDaysBetweenLunarEvents`, default 5).

Default chances for the new moons are 0.03–0.04, the same range as the originals (Blood 0.07, Harvest 0.035, Blue 0.02). Ordinary nights stay the norm. To make a moon rarer or more common, edit its `chance` and run `/ec lunarForecast recompute`.

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

---

## 6. Moon designs

All ten designs come from the reference sheet. They are packaged in the jar under `assets/ecmoons/textures/environment/`.
- `<moon>_moon_phases.png` is 512×256, the **same 4×2 phase layout as vanilla `moon_phases.png`** (phase *p* at column *p*%4, row *p*/4).
  - Each moon is 32×32 pixel art centred in a 128×128 cell, the same proportion as vanilla's 8-pixel moon in a 32-pixel cell. A halo fades out before the cell edge.
  - Phases shade the surface like vanilla: waning from the right, waxing from the left, with a faint rim at new moon.
  - The comparison sheet's labels, stars and background are not in any texture.
- `<moon>_moon_details.png` is an additive animated detail layer on the same quad. Quiet Moon has none by design.

| Static (always) | Animated (client option `animateMoonDetails`) |
|---|---|
| Surface, craters, colours, phases, halo, Frost's ice spikes | Blood fissure glow pulse; Blue, Harvest and Prospector glints and Frost cracks twinkling; Arcane rune pulse; Wisdom constellation twinkle; Mist wisps drifting sideways; Drift fragments bobbing |

Which design to draw comes from Enhanced Celestials' current event, which EC re-sends when a player joins or changes dimension. So players who join during an event see the right moon. Super variants use their base moon's design at EC's larger size.

Client options in `config/ecmoons-client.toml`: `useAddonMoonTextures`, `renderMoonDetails`, `animateMoonDetails`, `frostOverlay`, `frostOverlayOpacity`, `frostParticles`.

The source art pipeline is in `tools/art/`: `find_boxes.py` and `sample2.py` sample each moon from the reference, and `build_textures.py` builds the sheets.

---

## 7. Building

Pushing to `main` builds the jar on GitHub Actions (Java 8, ForgeGradle 5, official mappings) and boots a dedicated test server with the self-test.

To build locally: `./gradlew build` produces `build/libs/ecmoons-<version>.jar`. `libs/enhancedcelestials-2.0.9-1.16.5.jar` is the unmodified LGPL-3.0 Enhanced Celestials release, used only as a compile and dev-run dependency; it is not bundled into the add-on.

---

## 8. Tests

See [TESTING.md](TESTING.md) for what has been verified automatically and what still needs an in-game check.
