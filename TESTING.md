# Test status

Nothing below is marked as verified unless it was actually run. "Automated" means the GitHub Actions build of this repository ran it on a real Forge 1.16.5 **dedicated server** with Enhanced Celestials 2.0.9 loaded; the log of the latest run is on the `ci-logs` branch (`selftest.log`).

## Completed (automated, every push)

Two automated suites run on every push (GitHub Actions, Forge 36.2.39, Enhanced Celestials 2.0.9, this add-on). Output: `ci-logs` branch (server) and `ci-client` branch (client, including screenshots).

**Dedicated server self test** (`-Decmoons.selftest=true`, `selftest/SelfTest.java`) - real dedicated server, no client:
- Server starts with all add-on mixins applied (no client classes loaded on the dedicated server).
- All ten moons are present in Enhanced Celestials' lunar context; the three Super variants are preserved; exactly one base Blood Moon (no duplicates); missing event files are written into an existing world's events folder.
- `/ecmoons start <key>` for each of the ten moons: Enhanced Celestials reports the moon and the add-on effects switch to it; `/ecmoons active` works during each.
- Super Blood Moon inherits Blood Moon features (same strength).
- `/ecmoons end` returns to the ordinary moon; `/ecmoons list`, `settings`, `inspect loot`, `inspect block`, `inspect entity`, `reload` succeed.
- Arcane rounding table (0->0, 1->1, 2->2, 3->2, 4->3, 10->8, 39->29) and repair-per-material value.
- Blood rare-drop maths: 4% becomes exactly 5% (analytic) and ~5% in a 400k-sample Monte Carlo run.
- Config lists resolve correctly (crops include wheat but not grass; speed blocks include stone and iron ore but not stone bricks; ripe vs. unripe wheat).
- Blue Moon: bonus pools 1 and 2 are appended to `minecraft:chests/simple_dungeon` and the original pool is kept; the bonus condition is true only during Blue Moon, false with Luck 0, and pool 2 needs Luck 2.
- Placement records make a block non-natural and clearing them restores it.
- Spawn origins: a spawn-egg zombie is recorded as SPAWN_EGG and is not Wisdom-eligible; a natural zombie is eligible.
- Per moon (all ten): Quiet monster-cap multiplier 0.35; Blood keeps Enhanced Celestials' x3; footstep broadcast radius halves only during Mist while explosions stay 16; exhaustion x0.75 only during Harvest; exactly one extra crop random tick during Harvest and none for non-crops or other moons; enchanting the third slot costs 2 levels during Arcane and 3 otherwise (real `onEnchantmentPerformed`); anvil with 2 iron ingots on a 240-damage iron pickaxe repairs 154 during Arcane vs. 124 vanilla, cost 2.

**Client test** (`-Decmoons.clienttest=true`, `client/ClientSelfTest.java`) - a real Minecraft 1.16.5 client under a virtual display with software OpenGL, singleplayer world:
- Each of the ten moons (and Super Blood) is synced to the client and drawn with its design; screenshots `moon_00`..`moon_11` are published.
- Mist: fog fades in (partial after 3 s, full after 10 s); disappears in the Nether; returns after coming back to the overworld; fades out after the moon ends. Screenshots `mist_0_clear_night`, `mist_1_fog_night`.
- Frost: after ~20 s exposed the client receives the chilled state and the server's speed modifier is synced to the client; both are removed when the moon ends. Screenshot `frost_overlay_chilled`.
- Arcane: the real enchanting screen tooltip shows 3 levels without the lock and 2 with the server's Arcane lock.
- Drift: jump height 1.25 blocks vanilla vs. 1.65 blocks during Drift (below 2 blocks); fall damage reduced (server log shows multiplier 0.5); a player still in the air when Drift ends lands with the reduction (13 damage from 29 blocks instead of 26).

Bugs these suites found and that are fixed: a client-only `BlockPos` method used by `/ecmoons inspect block` (would have crashed a dedicated server), an enchanting-screen mixin that failed to apply (would have crashed the client when opening an enchanting table), and Drift's landing grace not recognising airborne players on the server.

## Verified by source review (not executed)

- **Lootr 1.16** passes the opening player's Luck into each per-player loot context (`LootrChestTileEntity`/`LootrBarrelTileEntity`/`LootrShulkerTileEntity`/`LootrChestMinecartEntity.fillWithLoot`: `builder.withLuck(player.getLuck()).withParameter(LootParameters.THIS_ENTITY, player)`), generates a player's inventory only once (`DataStorage.getInventory` → `ChestData.getInventory(player)` → `createInventory` only when absent) and stores it per player UUID. Present since Lootr's first 1.16 version.
- Every vanilla/Forge hook point (sky renderer moon section, enchanting tooltip, anvil `createResult`, fishing timers, jump exhaustion, footstep broadcast, random tick, `finalizeSpawn`, loot table loading, fog setup, name-plate gating) was checked against the decompiled Forge 36.2.39 / official-mappings 1.16.5 code before writing the mixins.

## Remaining in-game checks (need a client and players)

Priority order as requested. Each needs a player in the world; nothing here has been run yet.

1. **Dedicated-server startup with the full Crazy Craft Updated mod list** (the CI server only has Forge + Enhanced Celestials + this add-on). Watch the log for `Mixin` errors and `ecmoons` warnings (unknown ids in config lists are logged once).
2. **Scheduling and switching**: `/ecmoons list`, `/ecmoons start <key>` for each moon, `/ec lunarForecast`; confirm rise/set chat messages and that only one event is active.
3. **Joining during an active event**: log in while each moon is up; the correct design must show immediately; Mist fog fades in; Frost overlay appears only after exposure.
4. **Visual cleanup at dawn and on dimension change**: fog fades out, frost overlay disappears, speed modifier removed (`/ecmoons inspect player`), name tags visible again; go through a Nether portal and back during Mist/Frost.
5. **Lootr and IDAS**: during a Blue Moon, open a never-opened `idas:chests/pillager_fortress/pillager_basic` Lootr chest with player A, reopen (must not change), let the moon end and reopen (must not change), open with player B (own roll). `/ecmoons inspect loot` must list the IDAS table (it is skipped if the id differs on your server - check `latest.log`).
6. **Ore and XP exploits**: place an ore you silk-touched and mine it with Fortune during Prospector's/Wisdom Moon (no bonus/extra XP); mine natural ore (bonus ~25%/double XP); quarry/miner machines (no bonus); `/ecmoons inspect block` on placed vs natural ore; kill spawner mobs vs natural mobs during Wisdom/Blood.
7. **Enchanting and anvil cost sync** during an Arcane Moon: tooltip shows 1/2/2 levels and the same is deducted; leave the table open across dawn and enchant (still the discounted, displayed cost); anvil cost label equals levels deducted; "Too Expensive" unchanged for 40+.
8. **Drift transitions**: jump off a tall pillar just before dawn (`/ecmoons end` mid-fall): landing damage still halved; jump height ~1.6 blocks (cannot clear 2-block walls); combine with Jump Boost and Slow Falling potions; elytra flight unaffected.
9. **Performance**: check `/forge tps` with 20+ players outside during Frost (heat scan only runs for exposed players every 10 ticks) and with large farms during Harvest.
10. **Shaders/OptiFine**: Mist fog and moon detail layer with OptiFine (no shaders) and with any shader pack players use. Not tested; shader packs may override fog and sky rendering.
11. **Right-click harvesting** mod in Crazy Craft: crops harvested by right-click during Harvest Moon are doubled once (not twice, not zero).
12. **Fishing** during Blue Moon: bites noticeably sooner (~30%); with Lure III.
13. **Quiet Moon**: hostile spawning visibly lower; existing mobs remain; regen pauses for 12 s after hitting or being hit.
