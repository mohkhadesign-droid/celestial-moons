package net.ecmoons.selftest;

import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.feature.PlacedBlocks;
import net.ecmoons.feature.SpawnOrigins;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.monster.ZombieEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootContext;
import net.minecraft.loot.LootParameterSets;
import net.minecraft.loot.LootParameters;
import net.minecraft.loot.LootTable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.world.BlockEvent;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-side checks that need a real (non-fake) player: run by the client test on the integrated server thread.
 * Each method is called while the named moon is active (or with no moon, for baselines).
 */
public final class RealPlayerChecks {
    private static final String P = "[ECMOONS-CLIENTTEST] ";
    public static final AtomicInteger PASSED = new AtomicInteger();
    public static final AtomicInteger FAILED = new AtomicInteger();
    private static double bloodBaseline = -1;

    private RealPlayerChecks() {}

    private static void check(String what, boolean ok) {
        if (ok) {
            PASSED.incrementAndGet();
            EcMoons.LOGGER.info(P + "PASS: " + what);
        } else {
            FAILED.incrementAndGet();
            EcMoons.LOGGER.error(P + "FAIL: " + what);
        }
    }

    private static ServerPlayerEntity player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static BlockPos testPos(ServerPlayerEntity p) {
        return p.blockPosition().offset(3, 0, 0);
    }

    /** Extra items above the normal single drop of an ore, averaged over n mining simulations. */
    private static double oreBonusRate(ServerWorld w, BlockPos pos, ServerPlayerEntity miner, ItemStack tool, int n) {
        BlockState state = w.getBlockState(pos);
        LootTable table = w.getServer().getLootTables().get(state.getBlock().getLootTable());
        Random random = new Random(7);
        int extra = 0;
        for (int i = 0; i < n; i++) {
            LootContext ctx = new LootContext.Builder(w).withRandom(random)
                    .withParameter(LootParameters.ORIGIN, Vector3d.atCenterOf(pos))
                    .withParameter(LootParameters.TOOL, tool)
                    .withParameter(LootParameters.BLOCK_STATE, state)
                    .withParameter(LootParameters.THIS_ENTITY, miner)
                    .create(LootParameterSets.BLOCK);
            List<ItemStack> drops = table.getRandomItems(ctx);
            int count = 0;
            for (ItemStack s : drops) count += s.getCount();
            extra += Math.max(0, count - 1);
        }
        return extra / (double) n;
    }

    /** Prospector's Moon must be active. */
    public static void prospector(MinecraftServer server) {
        ServerPlayerEntity p = player(server);
        ServerWorld w = p.getLevel();
        check("prospector active for real-player checks", Moons.is(w, MoonType.PROSPECTOR));
        BlockPos pos = testPos(p);
        w.setBlock(pos, Blocks.IRON_ORE.defaultBlockState(), 3);
        PlacedBlocks.mark(w, pos, false);
        ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
        double natural = oreBonusRate(w, pos, p, pick, 4000);
        check(String.format("natural iron ore bonus rate %.3f (target 0.25)", natural), Math.abs(natural - 0.25) < 0.03);
        ItemStack silk = new ItemStack(Items.DIAMOND_PICKAXE);
        silk.enchant(Enchantments.SILK_TOUCH, 1);
        check("no bonus with Silk Touch", oreBonusRate(w, pos, p, silk, 1000) == 0);
        FakePlayer fake = FakePlayerFactory.getMinecraft(w);
        check("no bonus for a fake player (machines)", oreBonusRate(w, pos, fake, pick, 1000) == 0);

        // A real placement event (as fired when a player places a block) marks the ore as player-placed.
        w.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        BlockSnapshot snapshot = BlockSnapshot.create(w.dimension(), w, pos);
        w.setBlock(pos, Blocks.IRON_ORE.defaultBlockState(), 3);
        boolean cancelled = ForgeEventFactory.onBlockPlace(p, snapshot, Direction.UP);
        check("placement event accepted", !cancelled);
        check("placed ore is not natural", !PlacedBlocks.isNatural(w, pos));
        check("no bonus from a placed ore (place-and-break loop)", oreBonusRate(w, pos, p, pick, 1000) == 0);

        // Wisdom ore XP must not double while Prospector is up.
        check("ore XP unchanged outside Wisdom", oreXp(w, Blocks.COAL_ORE.defaultBlockState(), p, false) == 3);
        w.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
    }

    private static int oreXp(ServerWorld w, BlockState state, ServerPlayerEntity p, boolean placed) {
        BlockPos pos = testPos(p).above();
        w.setBlock(pos, state, 3);
        PlacedBlocks.mark(w, pos, placed);
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(w, pos, state, p);
        event.setExpToDrop(3);
        MinecraftForge.EVENT_BUS.post(event);
        w.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        PlacedBlocks.mark(w, pos, false);
        return event.getExpToDrop();
    }

    private static ZombieEntity zombie(ServerWorld w, ServerPlayerEntity p, SpawnReason reason) {
        ZombieEntity z = EntityType.ZOMBIE.create(w);
        z.moveTo(p.getX() + 2, p.getY(), p.getZ(), 0, 0);
        if (reason == SpawnReason.SPAWNER) SpawnOrigins.record(z, SpawnReason.SPAWNER);
        z.finalizeSpawn(w, w.getCurrentDifficultyAt(z.blockPosition()), reason, null, null);
        return z;
    }

    private static int mobXp(ZombieEntity z, net.minecraft.entity.player.PlayerEntity killer) {
        LivingExperienceDropEvent event = new LivingExperienceDropEvent(z, killer, 5);
        MinecraftForge.EVENT_BUS.post(event);
        return event.getDroppedExperience();
    }

    /** Wisdom Moon must be active. */
    public static void wisdom(MinecraftServer server) {
        ServerPlayerEntity p = player(server);
        ServerWorld w = p.getLevel();
        check("wisdom active for real-player checks", Moons.is(w, MoonType.WISDOM));
        check("natural zombie XP doubled (5 -> 10)", mobXp(zombie(w, p, SpawnReason.NATURAL), p) == 10);
        check("spawner zombie XP not doubled", mobXp(zombie(w, p, SpawnReason.SPAWNER), p) == 5);
        check("spawn-egg zombie XP not doubled", mobXp(zombie(w, p, SpawnReason.SPAWN_EGG), p) == 5);
        check("fake-player kill XP not doubled", mobXp(zombie(w, p, SpawnReason.NATURAL), FakePlayerFactory.getMinecraft(w)) == 5);
        check("natural coal ore XP doubled (3 -> 6)", oreXp(w, Blocks.COAL_ORE.defaultBlockState(), p, false) == 6);
        check("placed coal ore XP not doubled", oreXp(w, Blocks.COAL_ORE.defaultBlockState(), p, true) == 3);
    }

    private static double zombieRareRate(ServerWorld w, ServerPlayerEntity p, SpawnReason origin, int n) {
        ZombieEntity z = zombie(w, p, origin);
        LootTable table = w.getServer().getLootTables().get(EntityType.ZOMBIE.getDefaultLootTable());
        Random random = new Random(11);
        int rare = 0;
        for (int i = 0; i < n; i++) {
            LootContext ctx = new LootContext.Builder(w).withRandom(random)
                    .withParameter(LootParameters.THIS_ENTITY, z)
                    .withParameter(LootParameters.ORIGIN, z.position())
                    .withParameter(LootParameters.DAMAGE_SOURCE, DamageSource.playerAttack(p))
                    .withParameter(LootParameters.KILLER_ENTITY, p)
                    .withParameter(LootParameters.DIRECT_KILLER_ENTITY, p)
                    .withParameter(LootParameters.LAST_DAMAGE_PLAYER, p)
                    .create(LootParameterSets.ENTITY);
            for (ItemStack s : table.getRandomItems(ctx)) {
                if (s.getItem() == Items.IRON_INGOT || s.getItem() == Items.CARROT || s.getItem() == Items.POTATO) {
                    rare++;
                    break;
                }
            }
        }
        return rare / (double) n;
    }

    /** No moon active: baseline rare-drop rate for zombies (vanilla 2.5%). */
    public static void bloodBaseline(MinecraftServer server) {
        ServerPlayerEntity p = player(server);
        bloodBaseline = zombieRareRate(p.getLevel(), p, SpawnReason.NATURAL, 60000);
        check(String.format("zombie rare-drop baseline %.4f (vanilla 0.025)", bloodBaseline), Math.abs(bloodBaseline - 0.025) < 0.003);
    }

    /** Blood Moon must be active. */
    public static void blood(MinecraftServer server) {
        ServerPlayerEntity p = player(server);
        ServerWorld w = p.getLevel();
        check("blood active for real-player checks", Moons.is(w, MoonType.BLOOD));
        double natural = zombieRareRate(w, p, SpawnReason.NATURAL, 60000);
        check(String.format("Blood Moon zombie rare-drop rate %.4f (target 0.03125 = 1.25 x 0.025)", natural), Math.abs(natural - 0.03125) < 0.003);
        double spawner = zombieRareRate(w, p, SpawnReason.SPAWNER, 60000);
        check(String.format("spawner zombie rare-drop rate unchanged %.4f (baseline 0.025)", spawner), Math.abs(spawner - 0.025) < 0.003);
    }
}
