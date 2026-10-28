package net.ecmoons.selftest;

import net.ecmoons.EcMoons;
import net.ecmoons.feature.GluttonyFeatures;
import net.ecmoons.feature.war.WarArena;
import net.ecmoons.feature.war.WarFeatures;
import net.ecmoons.feature.war.WarRecords;
import net.ecmoons.feature.war.WarState;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Effects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraftforge.event.ForgeEventFactory;

/**
 * Real-player checks for the Gluttony and War moons, run on the integrated server thread by the client test.
 */
public final class WarPlayerChecks {
    private static final String P = "[ECMOONS-CLIENTTEST] ";
    private static Vector3d homePos;
    private static int homeXp;

    private WarPlayerChecks() {}

    private static void check(String what, boolean ok) {
        if (ok) {
            RealPlayerChecks.PASSED.incrementAndGet();
            EcMoons.LOGGER.info(P + "PASS: " + what);
        } else {
            RealPlayerChecks.FAILED.incrementAndGet();
            EcMoons.LOGGER.error(P + "FAIL: " + what);
        }
    }

    private static ServerPlayerEntity player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().get(0);
    }

    private static int count(ServerPlayerEntity p, net.minecraft.item.Item item) {
        int n = 0;
        for (int i = 0; i < p.inventory.getContainerSize(); i++) {
            ItemStack s = p.inventory.getItem(i);
            if (s.getItem() == item) n += s.getCount();
        }
        return n;
    }

    // ---- Gluttony ------------------------------------------------------------------------------------------------

    /** The real food-eaten event path (LivingEntityUseItemEvent.Finish) during the Gluttony Moon. */
    public static void gluttonyEat(MinecraftServer server) {
        ServerPlayerEntity p = player(server);
        GluttonyFeatures.reset(p);
        for (net.minecraft.item.Item food : new net.minecraft.item.Item[]{Items.BREAD, Items.BREAD, Items.COOKED_PORKCHOP}) {
            ItemStack stack = new ItemStack(food);
            ForgeEventFactory.onItemUseFinish(p, stack.copy(), 0, ItemStack.EMPTY);
        }
        check("gluttony (real player): bread, bread, porkchop = 2 bonus hearts (max " + p.getMaxHealth() + ")", p.getMaxHealth() == 24f);
    }

    // ---- War -----------------------------------------------------------------------------------------------------

    /** Give the player things that must come back exactly. */
    public static void warPrepare(MinecraftServer server) {
        ServerPlayerEntity p = player(server);
        p.setGameMode(GameType.SURVIVAL);
        p.inventory.clearContent();
        p.inventory.add(new ItemStack(Items.DIAMOND, 7));
        p.inventory.add(new ItemStack(Items.TORCH, 33));
        p.setItemSlot(EquipmentSlotType.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        p.setItemSlot(EquipmentSlotType.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        p.setExperienceLevels(5);
        homeXp = p.experienceLevel;
        homePos = p.position();
        p.inventoryMenu.broadcastChanges();
        WarFeatures.clearChampion(p);
    }

    public static void warEnrolled(MinecraftServer server) {
        ServerPlayerEntity p = player(server);
        check("war: player moved to the arena dimension", p.level.dimension().equals(WarArena.DIMENSION));
        check("war: recovery record written before stripping", WarRecords.exists(server, p.getUUID()));
        check("war: own items removed (no diamonds, torches, helmet, totem)", count(p, Items.DIAMOND) == 0 && count(p, Items.TORCH) == 0
                && p.getItemBySlot(EquipmentSlotType.HEAD).getItem() == Items.IRON_HELMET
                && p.getItemBySlot(EquipmentSlotType.OFFHAND).getItem() == Items.SHIELD);
        check("war: equal kit given (iron armour, sword, bow, 16 arrows, 16 beef)", count(p, Items.IRON_SWORD) == 1 && count(p, Items.BOW) == 1
                && count(p, Items.ARROW) == 16 && count(p, Items.COOKED_BEEF) == 16
                && p.getItemBySlot(EquipmentSlotType.CHEST).getItem() == Items.IRON_CHESTPLATE);
        check("war: adventure mode, no flight", p.gameMode.getGameModeForPlayer() == GameType.ADVENTURE && !p.abilities.mayfly);
        check("war: phase PREP", WarFeatures.phase(server) == WarState.Phase.PREP);
        check("war: no attacking during preparation", !WarFeatures.isFighting(p) && Boolean.FALSE.equals(WarFeatures.pvpOverride(p, p)));
    }

    public static void warFight(MinecraftServer server) {
        ServerPlayerEntity p = player(server);
        check("war: phase FIGHT after the countdown", WarFeatures.phase(server) == WarState.Phase.FIGHT);
        check("war: player is fighting", WarFeatures.isFighting(p));
        // item transfers: tossing the selected stack returns it, nothing lands in the arena
        int swords = count(p, Items.IRON_SWORD);
        p.inventory.selected = p.inventory.findSlotMatchingItem(new ItemStack(Items.IRON_SWORD));
        p.drop(true);
        World w = p.level;
        boolean items = !w.getEntitiesOfClass(ItemEntity.class, new AxisAlignedBB(p.blockPosition()).inflate(8)).isEmpty();
        check("war: tossed items stay in the inventory and no item entity appears", count(p, Items.IRON_SWORD) == swords && !items);
        // block protection
        check("war: arena blocks cannot be broken", !p.gameMode.destroyBlock(p.blockPosition().below()));
    }

    public static void warKnockout(MinecraftServer server) {
        ServerPlayerEntity p = player(server);
        p.hurt(DamageSource.GENERIC, 1000f);
        check("war: lethal damage is a knockout, not a death", p.isAlive() && p.getHealth() > 0);
        check("war: knocked-out player is a watcher", WarFeatures.watchers().contains(p.getUUID()) && !WarFeatures.isFighting(p));
        World w = p.level;
        check("war: no drops from the knockout", w.getEntitiesOfClass(ItemEntity.class, new AxisAlignedBB(-80, 0, -80, 80, 255, 80)).isEmpty());
        check("war: knockout empties the kit", count(p, Items.IRON_SWORD) == 0);
        check("war: watcher moved to the viewing gallery (y " + p.getY() + ")", p.getY() > WarArena.FLOOR_Y + 12);
        p.hurt(DamageSource.GENERIC, 5f);
        check("war: watchers take no damage", p.getHealth() == p.getMaxHealth());
        check("war: solo match with nobody left ends (phase " + WarFeatures.phase(server) + ")", WarFeatures.phase(server) == WarState.Phase.ENDING
                || WarFeatures.phase(server) == WarState.Phase.FIGHT);
    }

    public static void warRestored(MinecraftServer server) {
        ServerPlayerEntity p = player(server);
        check("war: session finished (" + WarFeatures.describe(server) + ")", WarFeatures.phase(server) == WarState.Phase.IDLE);
        check("war: back in the overworld near the original spot", p.level.dimension().equals(World.OVERWORLD)
                && p.position().distanceTo(homePos) < 3);
        check("war: own items restored exactly (7 diamonds, 33 torches, golden helmet, totem)", count(p, Items.DIAMOND) == 7
                && count(p, Items.TORCH) == 33 && p.getItemBySlot(EquipmentSlotType.HEAD).getItem() == Items.GOLDEN_HELMET
                && p.getItemBySlot(EquipmentSlotType.OFFHAND).getItem() == Items.TOTEM_OF_UNDYING);
        check("war: no kit items left", count(p, Items.IRON_SWORD) == 0 && count(p, Items.COOKED_BEEF) == 0 && count(p, Items.ARROW) == 0);
        check("war: XP restored (" + p.experienceLevel + ")", p.experienceLevel == homeXp);
        check("war: game mode restored", p.gameMode.getGameModeForPlayer() == GameType.SURVIVAL);
        check("war: recovery record deleted", !WarRecords.exists(server, p.getUUID()));
        check("war: arena removed after the match", WarArena.world(server) == null
                || WarArena.world(server).getBlockState(new net.minecraft.util.math.BlockPos(0, WarArena.FLOOR_Y, 0)).isAir());
        // a second restore must not duplicate anything
        check("war: restoring again does nothing (no duplication)", !WarRecords.restore(p) && count(p, Items.DIAMOND) == 7);
    }

    public static void champion(MinecraftServer server) {
        ServerPlayerEntity p = player(server);
        check("war champion: Haste I applied (" + p.getEffect(Effects.DIG_SPEED) + ")", WarFeatures.isChampion(p)
                && p.getEffect(Effects.DIG_SPEED) != null && p.getEffect(Effects.DIG_SPEED).getAmplifier() == 0);
        int before = WarFeatures.championTicks(p);
        WarFeatures.setChampionTicks(p, 30 * 60 * 20);
        check("war champion: winning again refreshes (does not stack)", WarFeatures.championTicks(p) == 30 * 60 * 20 && before <= 30 * 60 * 20);
        WarFeatures.clearChampion(p);
    }
}
