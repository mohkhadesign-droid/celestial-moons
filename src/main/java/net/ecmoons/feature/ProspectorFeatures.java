package net.ecmoons.feature;

import com.google.gson.JsonObject;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Matchers;
import net.ecmoons.config.Settings;
import net.ecmoons.net.NaturalBlockPacket;
import net.ecmoons.net.Network;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootContext;
import net.minecraft.loot.LootParameters;
import net.minecraft.loot.conditions.ILootCondition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.loot.GlobalLootModifierSerializer;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prospector's Moon: +1 resource (25% chance) from manually mined natural ores, and faster mining of natural
 * stone/ores. Eligibility is decided on the server only:
 * real player (not a FakePlayer/machine), no Silk Touch, block natural according to {@link PlacedBlocks}.
 */
public final class ProspectorFeatures {
    private static Settings cachedFor;
    private static Map<Block, ItemStack> cachedBonuses = new IdentityHashMap<>();

    private ProspectorFeatures() {}

    /** Bonus resource for a block (from prospector.oreBonuses), or null. */
    @Nullable
    public static synchronized ItemStack bonusFor(Block block) {
        Settings s = Settings.server();
        if (s != cachedFor) {
            Map<Block, ItemStack> map = new IdentityHashMap<>();
            for (String entry : s.list("prospector.oreBonuses")) {
                try {
                    String[] kv = entry.split("=", 2);
                    if (kv.length != 2) continue;
                    String itemPart = kv[1].trim();
                    int count = 1;
                    int star = itemPart.indexOf('*');
                    if (star > 0) {
                        count = Math.max(1, Integer.parseInt(itemPart.substring(star + 1).trim()));
                        itemPart = itemPart.substring(0, star).trim();
                    }
                    ResourceLocation blockId = new ResourceLocation(kv[0].trim());
                    ResourceLocation itemId = new ResourceLocation(itemPart);
                    if (!ForgeRegistries.BLOCKS.containsKey(blockId) || !ForgeRegistries.ITEMS.containsKey(itemId)) {
                        EcMoons.LOGGER.warn("Skipping Prospector's Moon mapping (unknown id): {}", entry);
                        continue;
                    }
                    Item item = ForgeRegistries.ITEMS.getValue(itemId);
                    map.put(ForgeRegistries.BLOCKS.getValue(blockId), new ItemStack(item, count));
                } catch (RuntimeException e) {
                    EcMoons.LOGGER.warn("Invalid Prospector's Moon mapping '{}': {}", entry, e.getMessage());
                }
            }
            cachedBonuses = map;
            cachedFor = s;
        }
        ItemStack stack = cachedBonuses.get(block);
        return stack == null ? null : stack.copy();
    }

    public static boolean isManualMiner(@Nullable Entity entity) {
        return entity instanceof ServerPlayerEntity && !(entity instanceof FakePlayer) && !((PlayerEntity) entity).isSpectator();
    }

    public static boolean speedEligible(World world, BlockState state) {
        return Matchers.block(Settings.get(world), "prospector.speedBlocks", state.getBlock()) || bonusForSide(world, state.getBlock());
    }

    private static boolean bonusForSide(World world, Block block) {
        // On the client the ore list is only used for speed prediction; resolve by id against the synced list.
        if (world.isClientSide) {
            ResourceLocation id = block.getRegistryName();
            if (id == null) return false;
            for (String entry : Settings.client().list("prospector.oreBonuses")) {
                if (entry.startsWith(id.toString() + "=")) return true;
            }
            return false;
        }
        return bonusFor(block) != null;
    }

    // ---- mining speed -------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        PlayerEntity player = event.getPlayer();
        World world = player.level;
        if (!Moons.is(world, MoonType.PROSPECTOR)) return;
        if (!speedEligible(world, event.getState())) return;
        BlockPos pos = event.getPos();
        if (pos == null) return;
        boolean natural;
        if (world.isClientSide) {
            natural = net.ecmoons.client.ClientState.isNatural(pos);
        } else {
            if (!isManualMiner(player)) return;
            natural = PlacedBlocks.isNatural(world, pos);
        }
        if (natural) event.setNewSpeed(event.getNewSpeed() * (float) Settings.get(world).d("prospector.miningSpeedMultiplier"));
    }

    /** Tell the client whether the block it started mining is natural, so its predicted break time matches. */
    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        World world = event.getWorld();
        if (world.isClientSide || !(event.getPlayer() instanceof ServerPlayerEntity)) return;
        if (!Moons.is(world, MoonType.PROSPECTOR)) return;
        BlockState state = world.getBlockState(event.getPos());
        if (!speedEligible(world, state)) return;
        boolean natural = isManualMiner(event.getPlayer()) && PlacedBlocks.isNatural(world, event.getPos());
        Network.send((ServerPlayerEntity) event.getPlayer(), new NaturalBlockPacket(event.getPos().immutable(), natural));
    }

    // ---- bonus resource (global loot modifier on block loot) ----------------------------------------------

    public static class BonusModifier extends LootModifier {
        public BonusModifier(ILootCondition[] conditions) {
            super(conditions);
        }

        @Nonnull
        @Override
        protected List<ItemStack> doApply(List<ItemStack> generatedLoot, LootContext context) {
            BlockState state = context.getParamOrNull(LootParameters.BLOCK_STATE);
            Entity miner = context.getParamOrNull(LootParameters.THIS_ENTITY);
            ItemStack tool = context.getParamOrNull(LootParameters.TOOL);
            Vector3d origin = context.getParamOrNull(LootParameters.ORIGIN);
            if (state == null || origin == null || !isManualMiner(miner)) return generatedLoot;
            ServerWorld world = context.getLevel();
            if (!Moons.is(world, MoonType.PROSPECTOR)) return generatedLoot;
            if (tool != null && EnchantmentHelper.getItemEnchantmentLevel(Enchantments.SILK_TOUCH, tool) > 0) return generatedLoot;
            ItemStack bonus = bonusFor(state.getBlock());
            if (bonus == null) return generatedLoot;
            if (!PlacedBlocks.isNatural(world, new BlockPos(origin))) return generatedLoot;
            if (context.getRandom().nextDouble() < Settings.server().d("prospector.bonusChance")) {
                generatedLoot.add(bonus);
            }
            return generatedLoot;
        }
    }

    public static class BonusSerializer extends GlobalLootModifierSerializer<BonusModifier> {
        @Override
        public BonusModifier read(ResourceLocation location, JsonObject object, ILootCondition[] conditions) {
            return new BonusModifier(conditions);
        }

        @Override
        public JsonObject write(BonusModifier instance) {
            return makeConditions(instance.conditions);
        }
    }

    /** Diagnostic snapshot for /ecmoons. */
    public static Map<String, String> describe(World world, BlockPos pos) {
        Map<String, String> out = new HashMap<>();
        PlacedBlocks.Data data = PlacedBlocks.data(world, pos);
        out.put("placedRecord", data == null ? "n/a" : String.valueOf(data.isPlaced(pos)));
        out.put("legacyChunk", data == null ? "n/a" : String.valueOf(data.isLegacy()));
        out.put("natural", String.valueOf(PlacedBlocks.isNatural(world, pos)));
        out.put("trackedType", String.valueOf(PlacedBlocks.isTracked(world.getBlockState(pos).getBlock())));
        ItemStack bonus = bonusFor(world.getBlockState(pos).getBlock());
        out.put("bonusResource", bonus == null ? "none" : bonus.getCount() + "x " + bonus.getItem().getRegistryName());
        return out;
    }
}
