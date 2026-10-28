package net.ecmoons.selftest;

import corgitaco.enhancedcelestials.LunarContext;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Matchers;
import net.ecmoons.config.MoonConfig;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.ArcaneCosts;
import net.ecmoons.feature.BloodMoonDrops;
import net.ecmoons.feature.BlueMoonLoot;
import net.ecmoons.feature.HarvestFeatures;
import net.ecmoons.feature.MistFeatures;
import net.ecmoons.feature.PlacedBlocks;
import net.ecmoons.feature.SpawnOrigins;
import net.ecmoons.feature.AnvilCostView;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropsBlock;
import net.minecraft.entity.EntityClassification;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.monster.ZombieEntity;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.inventory.container.RepairContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootContext;
import net.minecraft.loot.LootParameterSets;
import net.minecraft.loot.LootParameters;
import net.minecraft.loot.LootTable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.IWorldPosCallable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Automated checks run on a real dedicated server when started with -Decmoons.selftest=true (CI does this).
 * Everything that can be exercised without a connected client is tested here; results are logged as
 * "[ECMOONS-SELFTEST] PASS/FAIL ..." and the server stops itself afterwards.
 */
public final class SelfTest {
    private static final String P = "[ECMOONS-SELFTEST] ";
    private static final Deque<Step> STEPS = new ArrayDeque<>();
    private static int passed, failed, wait;
    private static MinecraftServer server;

    private SelfTest() {}

    private static final class Step {
        final String name;
        final int delayTicks;
        final Consumer<ServerWorld> action;

        Step(String name, int delayTicks, Consumer<ServerWorld> action) {
            this.name = name;
            this.delayTicks = delayTicks;
            this.action = action;
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            EcMoons.LOGGER.info(P + "PASS: " + what);
        } else {
            failed++;
            EcMoons.LOGGER.error(P + "FAIL: " + what);
        }
    }

    private static int cmd(String command) {
        int r = server.getCommands().performCommand(server.createCommandSourceStack(), command);
        EcMoons.LOGGER.info(P + "/" + command + " -> " + r);
        return r;
    }

    private static void step(String name, int delay, Consumer<ServerWorld> action) {
        STEPS.add(new Step(name, delay, action));
    }

    @SubscribeEvent
    public static void onStarted(FMLServerStartedEvent event) {
        server = event.getServer();
        EcMoons.LOGGER.info(P + "starting");
        step("static checks", 1, SelfTest::staticChecks);
        step("new moon static checks", 1, w -> NewMoonChecks.staticChecks(w, SelfTest::check));
        step("field guide recipe", 1, w -> {
            boolean patchouli = net.minecraftforge.fml.ModList.get().isLoaded("patchouli");
            boolean recipe = w.getServer().getRecipeManager().byKey(new net.minecraft.util.ResourceLocation("ecmoons", "field_guide")).isPresent();
            check("field guide: crafting recipe loaded only when Patchouli is installed (patchouli " + patchouli + ", recipe " + recipe + ")", patchouli == recipe);
        });
        step("shell projectiles", 1, w -> ShellChecks.setup(w, SelfTest::check));
        step("shell verify", 25, w -> ShellChecks.verify(w, SelfTest::check));
        step("shell exhaust", 25, w -> ShellChecks.verifyExhaust(w, SelfTest::check));
        step("shell after collapse", 25, w -> ShellChecks.verifyAfterCollapse(w, SelfTest::check));
        for (MoonType type : MoonType.MOONS) {
            step("start " + type.id(), 2, w -> check("/ecmoons start " + type.defaultKey, cmd("ecmoons start " + type.defaultKey) == 1));
            step("verify " + type.id(), 5, w -> {
                check(type.id() + " reported by Enhanced Celestials", Moons.reported(w) == type);
                check(type.id() + " add-on effects active", Moons.active(w) == type);
                check("/ecmoons active during " + type.id(), cmd("ecmoons active") == 1);
                moonChecks(w, type);
                NewMoonChecks.gluttony(w, type, SelfTest::check);
            });
        }
        step("super variant", 2, w -> check("start super blood", cmd("ecmoons start enhancedcelestials-super_blood_moon") == 1));
        step("super variant verify", 5, w -> check("super blood moon inherits Blood features", Moons.active(w) == MoonType.BLOOD
                && MoonType.isSuperVariant(Moons.currentEvent(w))));
        step("end", 2, w -> check("/ecmoons end", cmd("ecmoons end") == 1));
        step("after end", 5, w -> check("ordinary moon after /ecmoons end", Moons.active(w) == MoonType.NONE));
        step("gluttony after end", 1, w -> NewMoonChecks.gluttonyEnded(w, SelfTest::check));
        step("new moon commands", 1, w -> {
            check("/moonteams create", cmd("moonteams create red Red Kingdom") == 1);
            check("/moonteams create duplicate fails", cmd("moonteams create red Again") == 0);
            check("/moonteams list", cmd("moonteams list") == 1);
            check("/moonteams info", cmd("moonteams info red") == 0);
            check("/moonteams delete", cmd("moonteams delete red") == 1);
            check("/ecmoons war status", cmd("ecmoons war status") == 1);
            check("/ecmoons war records", cmd("ecmoons war records") == 0);
            check("/ecmoons war arena build", cmd("ecmoons war arena build") == 1);
            check("/ecmoons war arena clear", cmd("ecmoons war arena clear") == 1);
            check("/ecmoons reveal status", cmd("ecmoons reveal status") == 1);
            check("/ecmoons reveal scan (no players)", cmd("ecmoons reveal scan") == 0);
            check("/ecmoons war start now with nobody online is cancelled safely", cmd("ecmoons war start now") == 1);
        });
        step("war cancelled", 5, w -> check("war tournament cancelled for lack of players (" + net.ecmoons.feature.war.WarFeatures.describe(server) + ")",
                net.ecmoons.feature.war.WarFeatures.phase(server) == net.ecmoons.feature.war.WarState.Phase.IDLE));
        step("commands", 1, w -> {
            check("/ecmoons list", cmd("ecmoons list") > 0);
            check("/ecmoons settings mist", cmd("ecmoons settings mist") > 0);
            check("/ecmoons inspect loot", cmd("ecmoons inspect loot") > 0);
            BlockPos spawn = w.getSharedSpawnPos();
            check("/ecmoons inspect block", cmd("ecmoons inspect block " + spawn.getX() + " " + spawn.getY() + " " + spawn.getZ()) == 1);
            ZombieEntity z = EntityType.ZOMBIE.create(w);
            z.moveTo(spawn.getX() + 0.5, spawn.getY() + 1, spawn.getZ() + 0.5, 0, 0);
            z.setPersistenceRequired();
            w.addFreshEntity(z);
            check("/ecmoons inspect entity", cmd("ecmoons inspect entity " + z.getStringUUID()) == 1);
            z.remove();
            check("/ecmoons reload", cmd("ecmoons reload") == 1);
        });
        step("finish", 1, w -> {
            EcMoons.LOGGER.info(P + "SELFTEST COMPLETE passed=" + passed + " failed=" + failed);
            server.halt(false);
        });
        wait = 40;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null || STEPS.isEmpty()) return;
        if (wait-- > 0) return;
        Step step = STEPS.poll();
        try {
            step.action.accept(server.overworld());
        } catch (Throwable t) {
            failed++;
            EcMoons.LOGGER.error(P + "FAIL: exception in step '" + step.name + "'", t);
        }
        Step next = STEPS.peek();
        wait = next == null ? 0 : next.delayTicks;
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void staticChecks(ServerWorld w) {
        LunarContext ctx = Moons.context(w);
        check("Enhanced Celestials lunar context exists in the overworld", ctx != null);
        if (ctx == null) return;
        for (MoonType t : MoonType.MOONS) {
            check("event " + t.defaultKey + " loaded", ctx.getLunarEvents().containsKey(t.defaultKey));
        }
        for (String sup : new String[]{"enhancedcelestials-super_blood_moon", "enhancedcelestials-super_blue_moon", "enhancedcelestials-super_harvest_moon"}) {
            check("super variant " + sup + " preserved", ctx.getLunarEvents().containsKey(sup));
        }
        check("exactly one Blood Moon base event (no duplicates)", ctx.getLunarEvents().values().stream()
                .filter(e -> MoonType.of(e) == MoonType.BLOOD && !MoonType.isSuperVariant(e)).count() == 1);
        Path dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("enhancedcelestials/minecraft/overworld/lunar/events");
        check("event JSON written for ecmoons-mist_moon", Files.exists(dir.resolve("ecmoons-mist_moon.json")));

        Settings s = Settings.server();
        boolean allKeys = true;
        for (String key : MoonConfig.VALUES.keySet()) {
            try {
                s.s(key);
            } catch (RuntimeException e) {
                allKeys = false;
            }
        }
        check("every config key has a value", allKeys);

        int[][] arcane = {{0, 0}, {1, 1}, {2, 2}, {3, 2}, {4, 3}, {10, 8}, {39, 29}};
        boolean arcaneOk = true;
        for (int[] c : arcane) arcaneOk &= ArcaneCosts.discount(c[0], 0.75, 1) == c[1];
        check("Arcane rounding table 0->0 1->1 2->2 3->2 4->3 10->8 39->29", arcaneOk);
        check("Arcane repair per unit (250 max dmg, +25%) = 77", ArcaneCosts.repairPerUnit(250, 1.25) == 77);

        double q = BloodMoonDrops.secondChance(0.04, 1.25);
        check("Blood second chance makes 4% into 5%", Math.abs(0.04 + 0.96 * q - 0.05) < 1e-9);
        Random r = new Random(42);
        int hits = 0, n = 400000;
        for (int i = 0; i < n; i++) if (r.nextDouble() < 0.04 || r.nextDouble() < q) hits++;
        check("Blood Monte Carlo ~5% (" + (hits * 100.0 / n) + "%)", Math.abs(hits / (double) n - 0.05) < 0.002);

        check("harvest growth list includes wheat", Matchers.block(s, "harvest.growthBlocks", Blocks.WHEAT));
        check("harvest growth list excludes grass", !Matchers.block(s, "harvest.growthBlocks", Blocks.GRASS_BLOCK));
        check("prospector speed list includes stone", Matchers.block(s, "prospector.speedBlocks", Blocks.STONE));
        check("prospector speed list includes iron ore", Matchers.block(s, "prospector.speedBlocks", Blocks.IRON_ORE));
        check("prospector speed list excludes stone bricks", !Matchers.block(s, "prospector.speedBlocks", Blocks.STONE_BRICKS));
        check("immature wheat is not mature", !HarvestFeatures.isMature(Blocks.WHEAT.defaultBlockState()));
        check("ripe wheat is mature", HarvestFeatures.isMature(((CropsBlock) Blocks.WHEAT).getStateForAge(7)));

        LootTable dungeon = server.getLootTables().get(new ResourceLocation("minecraft", "chests/simple_dungeon"));
        check("Blue Moon bonus pool 1 appended to simple_dungeon", dungeon.getPool("ecmoons_blue_moon_bonus_1") != null);
        check("Blue Moon bonus pool 2 appended to simple_dungeon", dungeon.getPool("ecmoons_blue_moon_bonus_2") != null);
        check("original simple_dungeon pools kept", dungeon.getPool("main") != null || dungeon.getPool("pool1") != null);

        BlockPos pos = new BlockPos(0, 64, 0);
        PlacedBlocks.mark(w, pos, true);
        check("placed-block record makes a block non-natural", !PlacedBlocks.isNatural(w, pos));
        PlacedBlocks.mark(w, pos, false);
        check("clearing the record makes it natural again", PlacedBlocks.isNatural(w, pos));

        ZombieEntity egg = EntityType.ZOMBIE.create(w);
        egg.moveTo(0.5, 80, 0.5, 0, 0);
        egg.finalizeSpawn(w, w.getCurrentDifficultyAt(egg.blockPosition()), SpawnReason.SPAWN_EGG, null, null);
        check("spawn egg origin recorded (" + SpawnOrigins.origin(egg) + ")", "SPAWN_EGG".equals(SpawnOrigins.origin(egg)));
        check("spawn-egg zombie not Wisdom eligible", !SpawnOrigins.eligible(egg, s.list("wisdom.eligibleSpawnOrigins"), s.b("wisdom.untrackedMobsEligible")));
        ZombieEntity natural = EntityType.ZOMBIE.create(w);
        natural.finalizeSpawn(w, w.getCurrentDifficultyAt(natural.blockPosition()), SpawnReason.NATURAL, null, null);
        check("natural zombie Wisdom eligible", SpawnOrigins.eligible(natural, s.list("wisdom.eligibleSpawnOrigins"), s.b("wisdom.untrackedMobsEligible")));
    }

    private static LootContext chestContext(ServerWorld w, float luck) {
        return new LootContext.Builder(w).withParameter(LootParameters.ORIGIN, Vector3d.ZERO).withLuck(luck)
                .withRandom(new Random(1)).create(LootParameterSets.CHEST);
    }

    private static void moonChecks(ServerWorld w, MoonType type) {
        LunarContext ctx = Moons.context(w);
        BlueMoonLoot.MoonLuckCondition luck1 = new BlueMoonLoot.MoonLuckCondition(MoonType.BLUE, 1.0f);
        boolean blueBonus = luck1.test(chestContext(w, 1.0f));
        check("Blue bonus condition (luck 1) is " + (type == MoonType.BLUE) + " during " + type.id(), blueBonus == (type == MoonType.BLUE));
        if (type == MoonType.BLUE) {
            check("Blue bonus needs Luck: luck 0 gives no bonus", !luck1.test(chestContext(w, 0f)));
            check("second bonus roll needs Luck 2", !new BlueMoonLoot.MoonLuckCondition(MoonType.BLUE, 2.0f).test(chestContext(w, 1.0f)));
        }
        double cap = ctx.getCurrentEvent().getSpawnMultiplierForMonsterCategory(EntityClassification.MONSTER);
        if (type == MoonType.QUIET) check("Quiet monster cap multiplier 0.35 (" + cap + ")", Math.abs(cap - 0.35) < 1e-9);
        if (type == MoonType.BLOOD) check("Blood keeps Enhanced Celestials' x3 monster cap (" + cap + ")", Math.abs(cap - 3.0) < 1e-9);

        double step = MistFeatures.footstepRadius(w, SoundEvents.STONE_STEP, 16);
        double boom = MistFeatures.footstepRadius(w, SoundEvents.GENERIC_EXPLODE, 16);
        check("footstep radius during " + type.id() + " = " + step, step == (type == MoonType.MIST ? 8.0 : 16.0));
        check("explosion radius unchanged during " + type.id(), boom == 16.0);

        FakePlayer fake = FakePlayerFactory.getMinecraft(w);
        float ex = HarvestFeatures.exhaustion(fake, 1.0f);
        check("exhaustion multiplier during " + type.id() + " = " + ex, Math.abs(ex - (type == MoonType.HARVEST ? 0.75f : 1.0f)) < 1e-6);

        // Harvest growth: one random tick on wheat triggers exactly the configured extra tick(s) only during Harvest.
        int y = w.getHeight(Heightmap.Type.WORLD_SURFACE, 8, 8);
        BlockPos crop = new BlockPos(8, y + 1, 8);
        w.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState(), 3);
        w.setBlock(crop, Blocks.WHEAT.defaultBlockState(), 3);
        long before = HarvestFeatures.extraTicks();
        w.getBlockState(crop).randomTick(w, crop, new Random(5));
        long extra = HarvestFeatures.extraTicks() - before;
        check("extra crop random ticks during " + type.id() + " = " + extra, extra == (type == MoonType.HARVEST ? 1 : 0));
        long beforeGrass = HarvestFeatures.extraTicks();
        w.setBlock(crop.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        w.getBlockState(crop.below()).randomTick(w, crop.below(), new Random(5));
        check("no extra random ticks for non-crops during " + type.id(), HarvestFeatures.extraTicks() == beforeGrass);
        w.setBlock(crop, Blocks.AIR.defaultBlockState(), 3);

        // Arcane enchanting: a table opened now locks the discount; 3 levels -> 2 during Arcane.
        fake.experienceLevel = 30;
        EnchantmentContainer table = new EnchantmentContainer(0, fake.inventory, IWorldPosCallable.NULL);
        fake.containerMenu = table;
        fake.onEnchantmentPerformed(new ItemStack(Items.IRON_SWORD), 3);
        int spent = 30 - fake.experienceLevel;
        check("enchanting third slot costs " + spent + " levels during " + type.id(), spent == (type == MoonType.ARCANE ? 2 : 3));

        // Arcane anvil: repairing a badly damaged iron pickaxe with iron ingots.
        RepairContainer anvil = new RepairContainer(0, fake.inventory, IWorldPosCallable.NULL);
        fake.containerMenu = anvil;
        ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
        pick.setDamageValue(240);
        anvil.getSlot(0).set(pick);
        anvil.getSlot(1).set(new ItemStack(Items.IRON_INGOT, 2));
        anvil.createResult();
        int cost = ((AnvilCostView) anvil).ecmoons$cost();
        ItemStack out = anvil.getSlot(2).getItem();
        // Iron pickaxe: 250 durability. Vanilla restores 62 per ingot; Arcane restores 77 (62 * 1.25).
        if (type == MoonType.ARCANE) {
            check("Arcane anvil: 2 ingots repair 154 (dmg 240 -> 86), cost 2: cost " + cost + ", units "
                    + anvil.repairItemCountCost + ", dmg " + out.getDamageValue(),
                    cost == 2 && anvil.repairItemCountCost == 2 && out.getDamageValue() == 86);
        } else if (type == MoonType.BLOOD) {
            check("vanilla anvil outside Arcane: 2 ingots repair 124 (dmg 240 -> 116), cost 2: cost " + cost + ", dmg "
                    + out.getDamageValue(), cost == 2 && out.getDamageValue() == 116);
        }
        fake.containerMenu = fake.inventoryMenu;
    }
}
