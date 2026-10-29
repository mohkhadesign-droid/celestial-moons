# Test status

Nothing below is marked as verified unless it was actually run. "Automated" means the GitHub Actions build of this repository ran it on a real Forge 1.16.5 **dedicated server** with Enhanced Celestials 2.0.9 loaded; the log of the latest run is on the `ci-logs` branch (`selftest.log`).

## Completed (automated, every push)

Two automated suites run on every push (GitHub Actions, Forge 36.2.39, Enhanced Celestials 2.0.9, this add-on). Output: `ci-logs` branch (server) and `ci-client` branch (client, including screenshots).

**Dedicated server self test** (`-Decmoons.selftest=true`, `selftest/SelfTest.java`) - real dedicated server, no client:
- Server starts with all add-on mixins applied (no client classes loaded on the dedicated server).
- All fourteen moons are present in Enhanced Celestials' lunar context; the three Super variants are preserved; exactly one base Blood Moon (no duplicates); missing event files are written into an existing world's events folder.
- `/ecmoons start <key>` for each of the fourteen moons: Enhanced Celestials reports the moon and the add-on effects switch to it; `/ecmoons active` works during each.
- Super Blood Moon inherits Blood Moon features (same strength).
- `/ecmoons end` returns to the ordinary moon; `/ecmoons list`, `settings`, `inspect loot`, `inspect block`, `inspect entity`, `reload` succeed.
- Arcane rounding table (0->0, 1->1, 2->2, 3->2, 4->3, 10->8, 39->29) and repair-per-material value.
- Blood rare-drop maths: 4% becomes exactly 5% (analytic) and ~5% in a 400k-sample Monte Carlo run.
- Config lists resolve correctly (crops include wheat but not grass; speed blocks include stone and iron ore but not stone bricks; ripe vs. unripe wheat).
- Blue Moon: bonus pools 1 and 2 are appended to `minecraft:chests/simple_dungeon` and the original pool is kept; the bonus condition is true only during Blue Moon, false with Luck 0, and pool 2 needs Luck 2.
- Placement records make a block non-natural and clearing them restores it.
- Spawn origins: a spawn-egg zombie is recorded as SPAWN_EGG and is not Wisdom-eligible; a natural zombie is eligible.
- Per moon (all ten): Quiet monster-cap multiplier 0.35; Blood keeps Enhanced Celestials' x3; footstep broadcast radius halves only during Mist while explosions stay 16; exhaustion x0.75 only during Harvest; exactly one extra crop random tick during Harvest and none for non-crops or other moons; enchanting the third slot costs 2 levels during Arcane and 3 otherwise (real `onEnchantmentPerformed`); anvil with 2 iron ingots on a 240-damage iron pickaxe repairs 154 during Arcane vs. 124 vanilla, cost 2.

- **Four new moons (server):**
  - Feast → Gluttony migration: an `ecmoons-feast_moon.json` becomes `ecmoons-gluttony_moon.json` with its chance kept; the old file is renamed `.migrated`.
  - Moon teams: same-team check, reassigning moves a player (one team each), assigning to a missing team fails, NBT save/load round trip, deleting a team removes its members. `/moonteams create` (and duplicate refused), `list`, `info`, `delete`.
  - Thread: two teammates 6 blocks apart bond and a third 30 blocks away does not; a stone wall between them breaks the bond (and does not when `requireLineOfSight=false`); 1 teammate = 5%, 3 = 15%, 5 = capped 15%; 10 damage with 15% = 8.5; starvation, drowning and void damage are never reduced.
  - Revealing: an underground player is marked at the surface with the same x/z; a player above the surface is marked where they are; the exempt tag and spectator mode hide a player.
  - Gluttony (during each of the 14 moons): beef + cooked beef = 1 heart, no instant heal, capped at 4, death resets and clamps health, progress builds again; no bonus during other moons; the bonus is removed and health clamped when the moon is not up. Categories: raw/cooked share one, rotten flesh never counts, unlisted items are their own category.
  - War arena: the `ecmoons:war_arena` dimension is loaded from the data pack in a new world and has no Enhanced Celestials lunar context; the built-in arena has 16 distinct cells, each with a floor and two free blocks; gates are iron bars and open; the gallery has a floor; the arena is fully removed afterwards. `/ecmoons war status`, `records`, `arena build`, `arena clear`, `/ecmoons reveal status`, `reveal scan`; `/ecmoons war start now` with nobody online is cancelled safely.

**Client test** (`-Decmoons.clienttest=true`, `client/ClientSelfTest.java`) - a real Minecraft 1.16.5 client under a virtual display with software OpenGL, singleplayer world:
- Each of the fourteen moons (and Super Blood) is synced to the client and drawn with its design; screenshots `moon_00`..`moon_14` and `moon_99_super_blood` are published.
- Mist: fog fades in (partial after 3 s, full after 10 s); disappears in the Nether; returns after coming back to the overworld; fades out after the moon ends. Screenshots `mist_0_clear_night`, `mist_1_fog_night`.
- Frost: after ~20 s exposed the client receives the chilled state and the server's speed modifier is synced to the client; both are removed when the moon ends. Screenshot `frost_overlay_chilled`.
- Arcane: the real enchanting screen tooltip shows 3 levels without the lock and 2 with the server's Arcane lock.
- Drift: jump height 1.25 blocks vanilla vs. 1.65 blocks during Drift (below 2 blocks); fall damage reduced (server log shows multiplier 0.5); a player still in the air when Drift ends lands with the reduction (13 damage from 29 blocks instead of 26).
- Rules that need a real (non-fake) player, run on the integrated server with the test player:
  - Prospector's Moon: natural iron ore gives the extra resource 24.4% of the time over 4,000 simulated breaks (target 25%). The bonus is 0 with Silk Touch, 0 for a fake player (machines), and 0 after a real placement event marks the ore as placed (closing the place-and-break loop).
  - Wisdom Moon: a natural zombie's XP is doubled (5 → 10). Spawner, spawn-egg and fake-player kills are not doubled. Natural coal ore XP is doubled (3 → 6); placed coal ore is not, and ore XP is not doubled outside Wisdom.
  - Blood Moon: zombie rare drops (iron ingot, carrot, potato) over 60,000 simulated player kills each: 2.61% baseline, 3.11% during Blood Moon (target 3.125%), 2.61% for spawner zombies.

- **Revealing Moon (real client):** a preview blink passes a half-closed frame, reaches the closed frame and reopens (screenshots `reveal_0_open`, `reveal_1_half`, `reveal_2_closed`); a server-timed search (`/ecmoons reveal warn`) shuts the eye at the end of the countdown and reopens it after the scan; a scan delivers the marker, the marker is drawn in the world through a hill with its distance/direction label (`reveal_3_marker`), and disappears after 8 s.
- **Thread Moon:** the HUD indicator renders (`thread_hud`, with a locally set value: one test player cannot bond).
- **Gluttony Moon (real player, real eating event):** bread, bread, porkchop = 2 bonus hearts (max health 24 on the client too); the hearts are gone when the moon ends (`gluttony_hearts`).
- **War Moon (real player, solo test match):** the player is moved to the arena dimension; the recovery record is written before stripping; diamonds, torches, golden helmet and totem are removed; the equal kit is given; adventure mode, no flight; no attacking during the 10 s preparation; FIGHT after the countdown; tossing an item keeps it in the inventory and creates no item entity; arena blocks can't be broken; lethal damage is a knockout, not a death, with no drops, kit emptied, moved to the gallery, and no damage taken there; the match ends with no winner; everyone is back in the overworld at the same spot with exactly 7 diamonds, 33 torches, the golden helmet and totem, XP level 5 and survival mode; the record is deleted, the arena removed, and a second restore changes nothing. War Champion: Haste I is applied, and winning again refreshes instead of stacking. Screenshots `war_0_cell`, `war_1_fight`, `war_2_gallery`.

- **Atmosphere (real client, every push):** for each of the 14 moons through `/ecmoons atmosphere preview`: the blend is partial 2 s into the 8 s transition and complete afterwards; the sky colour moves towards the moon's profile; the light-map grading is active; particles stay within the MEDIUM cap; Frost, War, Drift and Arcane spawn their ambient particles outdoors; the sky layers (Arcane ribbons, Drift streaks, Blood wisps, War smoke, Thread strands, Blue/Wisdom stars) are drawn; Wisdom's constellation lines appear. Screenshots `atmo_00`..`atmo_14` (horizon) and `*_sky`. Also: after `preview off` everything fades out and the sky colour returns exactly to the untouched value; quality OFF shows no atmosphere and no particles; LOW never exceeds 8 particles, HIGH exceeds 8 but never 35; the real War Moon battle warning builds turbulence and cancelling ends it; the War summon pulse; inside a closed stone room no particles spawn inside and sky exposure drops; an exposed ore wall in view gets glints while ores hidden directly behind it never do; a thread is drawn between the player and a stand-in, fades out over ~2.5 s; the atmosphere follows a real Enhanced Celestials Blood Moon, disappears in the Nether, fades back in on return and fades out when the event ends. The light-map hook was also checked to coexist with Enhanced Celestials' own light-map injection (an early version caused a mixin warning there; fixed).

Bugs these suites found and that are fixed: a client-only `BlockPos` method used by `/ecmoons inspect block` (would have crashed a dedicated server), an enchanting-screen mixin that failed to apply (would have crashed the client when opening an enchanting table), Drift's landing grace not recognising airborne players on the server, and (in this update) a War Moon return that would have sent a player saved mid-air to world spawn instead of the ground below.

## Afterimage Moon, Shell Moon and the Lunar Field Guide (build 44, automated)

**Dedicated server** (318 checks, 0 failures), with real projectile entities against a test shell floating in the air, NoAI husks and a pig on both sides:
- An arrow fired from outside never reaches the husk inside; one fired from inside never reaches the husk outside.
- An arrow moving 24 blocks per tick (it would jump across the whole shell in one tick) is stopped.
- An arrow flying entirely outside the shell, away from it, is untouched.
- An intercepted ender pearl teleports nobody (its owner stays put); an intercepted splash potion of Harming hurts nothing on the far side; an intercepted small fireball burns and explodes nothing inside.
- Each pick-up-able arrow comes back as exactly one arrow item, and the trident as exactly one trident item (no duplicates, nothing lost, no projectile left flying).
- 7 projectiles intercepted; durability 1000 − 6×10 − 30 (the fireball counts as explosive) = 910.
- A shell with 15 durability stops the arrow that empties it, collapses, and the next arrow hits the husk.
- The field guide recipe is loaded when Patchouli is present, and its result is `patchouli:guide_book` with `patchouli:book = ecmoons:field_guide`.

**Real client, real player** (216 checks, 0 failures; screenshots `afterimage_*`, `shell_*`, `guide_*` on `ci-client`):
- Frame Step: three presses of the Moon Ability key (sent through the real key handler) move the player exactly 2.00, 3.00 and 4.00 blocks (9.00 in total) in the look direction; the input window and the final attack window show on the HUD; afterimages are received and drawn.
- The first real melee hit (`gameMode.attack`) after step 3 gets knockback 0.64 instead of 0.4 (+60%), once; the next hit is a normal 0.4.
- On cooldown the key does nothing; a second press while the first step is still moving breaks the sequence, applies the slowdown and starts the cooldown; the slowdown wears off after 10 ticks.
- A wall 1.5 blocks ahead stops the step before it (the player ends at the wall face minus half their width, never inside).
- Shell: pressing the key without crouching does nothing; crouch + hold shows the charge, the shell deploys after the charge, is drawn, and exists on the server; releasing the key collapses it, starts the 20 s cooldown and the shell fades on the client.
- Primed TNT 2.5 blocks away: 9.2 damage without a shell, 2.3 with one (exactly 75% less), same player, same armour/effects.
- The Patchouli book loads with 3 categories and 21 entries, opens, and the Moon Ability, Frame Step, Shell, Revealing and War pages were rendered (screenshots `guide_*`).

**Bugs these tests found and that are fixed in b44:** the step teleport reset the server's idea of where the player looks, so steps 2 and 3 veered south; the field guide pages were in the wrong folder for Patchouli 1.16 (book opened empty); the guide recipe used a recipe type that only exists in Patchouli 1.17+.

**Still needs checking on your server (Afterimage/Shell):**
- Shell placement in a Flan claim and a World Protector/YAWP area where you have no build permission (must say it can't be raised there). The check posts Forge's block-place event; only a stand-in protection listener was tested.
- Modded projectiles from the pack (guns, magic): anything that isn't a projectile entity can't be stopped; add entity ids to `extraProjectileEntities` for modded projectiles that don't extend vanilla's class.
- Frame Step with real latency (players far from the server): the input window is 5 server ticks after a step ends.
- Two players: Shell blocking each other's arrows both ways, melee inside the shell, the afterimages seen by the other player.

## Wisp Moon (automated)

**Dedicated server** (fake player, real wisp/observer entities, a stone floor and wall):
- Piloting starts and a wisp appears at the player's eyes; the game mode is unchanged (not spectator).
- The wisp flies down to the floor over an oak pressure plate without pressing it, then east until a stone wall stops it (it ends at the wall face minus half its width, never inside).
- Three observers are placed on the wall face at different heights, all facing away from the wall; a fourth is refused at the limit; dismissing one leaves two.
- Placement is refused where a protection listener (stand-in for Flan / World Protector) cancels block placement, and when no block face is in reach.
- A real arrow destroys an observer.
- Flying straight up for 60 blocks stops at exactly 48.00 blocks from the body; a point 2000 blocks away counts as unloaded/out of range.
- Damage to the body ends piloting, removes the wisp and starts the cooldown.
- Viewing an observer in range works; moving the body ends it; an observer 60+ blocks away can't be viewed.
- Logging out removes the player's observers; when the moon ends, piloting stops and no wisp or observer entity is left.

**Real client, real player** (screenshots `wisp_*`):
- The Moon Ability key opens the Wisp menu; Pilot Wisp closes it and switches the camera to the wisp; the game mode stays survival.
- The wisp flies with movement input (4.9 blocks in 12 ticks) while the body stays exactly where it was; a wall stops it.
- The real Use key places an observer on the wall (HUD shows 1/3); looking back, the player's own body is drawn.
- The Moon Ability key returns the camera to the body and the wisp disappears.
- The menu then lists observer 1 in range and hides Pilot Wisp during the cooldown; viewing switches the camera to the observer; trying to turn 150° is clamped to 60°.
- Real damage to the body returns the camera at once.
- Ending the moon while piloting returns the camera, and no wisp or observer is left on the client.

**Not covered by the automated tests (check on your server):**
- The Escape key while piloting/viewing (handled by cancelling the pause screen when Escape is held; not pressed in the test).
- Flan claims and World Protector/YAWP areas with real permissions (only a stand-in listener was tested).
- Two players: another player seeing the wisp, the body and observers, and destroying an observer; PvP/claim rules on that hit.
- Death, logout and dimension change while piloting with a real client (the server-side cleanup is tested; the camera is reset when the server says so and by vanilla on respawn/relog).
- Servers with a small view distance (the reachable area is limited to chunks the body already has loaded).

## Verified by source review (not executed)

- **Lootr 1.16** passes the opening player's Luck into each per-player loot context (`LootrChestTileEntity`/`LootrBarrelTileEntity`/`LootrShulkerTileEntity`/`LootrChestMinecartEntity.fillWithLoot`: `builder.withLuck(player.getLuck()).withParameter(LootParameters.THIS_ENTITY, player)`), generates a player's inventory only once (`DataStorage.getInventory` → `ChestData.getInventory(player)` → `createInventory` only when absent) and stores it per player UUID. Present since Lootr's first 1.16 version.
- Every vanilla/Forge hook point (sky renderer moon section, enchanting tooltip, anvil `createResult`, fishing timers, jump exhaustion, footstep broadcast, random tick, `finalizeSpawn`, loot table loading, fog setup, name-plate gating) was checked against the decompiled Forge 36.2.39 / official-mappings 1.16.5 code before writing the mixins.

## Remaining in-game checks (need your modpack and real players)

### Four new moons

These parts could not be run with a single automated player and the bare test setup:

A. **Existing world gets the arena dimension.** The tests create a new world. Data-pack dimensions are normally added to existing 1.16.5 worlds as well, but confirm it on a copy of your world: `/ecmoons war status` must say "Arena dimension: loaded". Singleplayer worlds show Mojang's "Worlds using Experimental Settings are not supported" prompt because of this dimension (click Proceed); dedicated servers don't show it.
B. **A real multi-player War Moon match** (2+ players): PvP between fighters after the gates open, sudden death ring damage, winner announcement with moon team, champion aura visible to others, `/warmoon spectate` gallery viewers.
C. **Disconnect and restart recovery with real players:** log out mid-match, log back in (restored, forfeit); stop the server mid-match, restart, log in (restored); `/ecmoons war records` before and after.
D. **Curios and other equipment mods:** with Curios accessories equipped, enrol and return (accessories back exactly). If a player has items in any other item-holding capability, they are refused with a message naming it (expected); add it to `accessoryCapabilities` or `ignoredCapabilities` as appropriate.
E. **Flan Warfare deferral:** start a flag capture, then `/ecmoons war start`: the battle must be postponed ("A claim raid is in progress…"). The integration is by reflection on `net.flanwarfare.core.WarCore.get().capture().activeCount()`, checked against your repository's source but not run.
F. **Grave/corpse mods and Lootr** in the arena: a knockout never fires a death, so graves shouldn't trigger; confirm no grave appears.
G. **Thread Moon with two or more real teammates:** the bond percentage on the HUD, the rose threads (visible to the team only, not to other players), the 3 s fade, and actual damage taken.
H. **Revealing Moon with other players** (two or more real clients; nothing below was tested with a second player):
  1. Warnings, markers (other players within 256 blocks, never your own), eye glow and exclusions (spectators, exempt tag, protected areas, arena players) unchanged; no coordinates in chat.
  2. One golden ripple starts at completion (not when the eye opens), crosses the whole sky, stays fixed to the sky while you turn, and never repeats per detected player.
  3. Two players watch the same wave at the same progress; a player under a roof does not see it through the roof but is still revealed.
  4. Staring 2 s at the open eye binds; looking away releases at once; a roof prevents it; falling and knockback still work while bound; also try third person (both views).
  5. Invisible player: visible to the other player during the reveal; invisible again with the right time left afterwards; milk during the reveal = stays visible afterwards; drinking a potion during the reveal does not hide.
  6. Read Your Enemy: only while revealed, 1 s aim, within 32 blocks, clear view; ends at once when you look away, a wall comes between or the reveal ends; shows only name, health, absorption and effects.
  7. Hostiles near a revealed player walk to the old spot (not following them), at most 6, and a mob already fighting someone keeps its target.
  8. Amber edges and colour loss fade with each player's own timer and are gone at zero (try two players revealed by different scans).
  9. Death, `/ecmoons end`, dimension change, relog and becoming exempt leave no bind, visibility override, panel, investigation or colour behind.
  10. Spawn protection, Flan claims, World Protector regions, arena exclusions and other moons behave as before.
I. **Gluttony with modded foods** from the pack (each counts as its own category unless grouped in `foodCategories`) and relogging mid-night (hearts kept).

### Atmosphere

J. **With your modpack's renderer:** OptiFine and any shader pack, and sky/fog mods (e.g. better-fog or sky mods in Crazy Craft): check the sky colour, haze, light tint and sky layers still appear, or set `quality` LOW/OFF. Only vanilla rendering (Mesa software OpenGL) was tested.
K. **Performance on real hardware** with many players: FPS with MEDIUM and HIGH during War (embers) and Frost (crystals); the particle cap is per player and fixed, but sky layers cost a little GPU time.
L. **Multiplayer visuals:** Thread threads between two real teammates (only that team sees them); Gluttony bursts seen by nearby players; War build-up for everyone online.
M. **Taste:** colours and strengths are first passes from your palettes. Every value is in `[atmosphere_<moon>]` and reloads live with `/ecmoons reload`.

### Original ten moons

The automated runs use only Forge + Enhanced Celestials + this add-on, one player, software rendering. These still need checking on the Crazy Craft Updated server:

1. **Dedicated-server startup with the full mod list.** Watch `latest.log` for `Mixin` errors and `ecmoons` warnings (unknown ids in config lists are logged once). Another mod targeting the same vanilla methods is the most likely source of trouble.
2. **Scheduling over real nights.** Let a few in-game days pass with `/ec lunarForecast` and confirm the new moons appear between ordinary nights at the configured chances.
3. **Joining and reconnecting during an active event**, with a second player: correct moon design immediately, Mist fog fades in, the Frost overlay only after exposure, the other player's name tag hidden beyond ~22 blocks in Mist.
4. **Lootr + IDAS.** During a Blue Moon:
   - open a never-opened `idas:chests/pillager_fortress/pillager_basic` Lootr chest with player A, then reopen it (it must not change);
   - end the moon and reopen (still unchanged);
   - open it with player B (B gets their own roll).
   - `/ecmoons inspect loot` must list the IDAS table. If it's missing, the table id differs on your server; check `latest.log`.
5. **Ore and XP exploits with modpack machines.** Quarries and miners must get no Prospector bonus and no Wisdom XP. Check builders' wands and schematic printers: if they don't fire Forge placement events, blocks they place look natural (a documented limit). Use `/ecmoons inspect block` to check.
6. **Enchanting and anvil mods** in the pack (anything that replaces the enchanting table or handles `AnvilUpdateEvent`): those paths intentionally get no Arcane discount.
7. **Right-click harvesting** mod: crops harvested by right-click during Harvest Moon should be doubled once, not twice and not zero.
8. **Fishing** during Blue Moon: bites noticeably sooner (~30%), also with Lure III.
9. **Quiet Moon**: noticeably fewer new hostile mobs; existing mobs remain; regeneration pauses for 12 s after hitting or being hit.
10. **Performance**: `/forge tps` with many players outside during Frost (the heat scan runs only for exposed players, every 10 ticks) and with large farms during Harvest.
11. **OptiFine and shader packs**: the Mist fog, frost overlay and moon detail layer have only been checked with vanilla rendering (Mesa software OpenGL). Shader packs may override fog and sky drawing.
12. **Drift with movement mods and elytra**: jump boost and slow-fall stacking with other movement mods in the pack.
