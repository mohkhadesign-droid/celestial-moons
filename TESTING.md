# Test status

Nothing below is marked as verified unless it was actually run. "Automated" means the GitHub Actions build of this repository ran it on a real Forge 1.16.5 **dedicated server** with Enhanced Celestials 2.0.9 loaded; the log of the latest run is on the `ci-logs` branch (`selftest.log`).

## Completed (automated, every push)

AUTOMATED_RESULTS

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
