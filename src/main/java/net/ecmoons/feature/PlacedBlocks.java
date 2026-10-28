package net.ecmoons.feature;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.ecmoons.EcMoons;
import net.ecmoons.config.Matchers;
import net.ecmoons.config.Settings;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.PistonBlockStructureHelper;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.INBT;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkPrimerWrapper;
import net.minecraft.world.chunk.IChunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.event.world.PistonEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Placement history used to decide whether a stone/ore block is natural (Prospector's and Wisdom Moon).
 * <p>
 * A chunk capability stores the positions of tracked blocks (ores, natural stone types) that were placed by an
 * entity, created by flowing fluids (cobble/stone generators) or moved by a piston. It is saved with the chunk.
 * A block is "natural" when its position has no such record. Chunks that were saved before the add-on was
 * installed are flagged "legacy" because their history is unknown (see prospector.legacyChunkPolicy).
 * <p>
 * Limits (documented in the README): blocks set directly by mods without firing Forge placement events, blocks
 * moved by non-piston contraptions, and pre-installation builds in legacy chunks cannot be identified.
 */
public final class PlacedBlocks {
    private static final ResourceLocation KEY = new ResourceLocation(EcMoons.MOD_ID, "placed");
    /** Set while the add-on asks protection mods "could this player place here?" (nothing is actually placed). */
    public static boolean probing;

    @CapabilityInject(Data.class)
    public static Capability<Data> CAP = null;

    private PlacedBlocks() {}

    public static void registerCapability() {
        CapabilityManager.INSTANCE.register(Data.class, new Capability.IStorage<Data>() {
            @Nullable
            @Override
            public INBT writeNBT(Capability<Data> capability, Data instance, Direction side) {
                return instance.save(null);
            }

            @Override
            public void readNBT(Capability<Data> capability, Data instance, Direction side, INBT nbt) {
                if (nbt instanceof CompoundNBT) instance.load((CompoundNBT) nbt);
            }
        }, Data::new);
    }

    /** Blocks whose placement is recorded: everything any moon treats as "natural only". */
    public static boolean isTracked(Block block) {
        Settings s = Settings.server();
        return Matchers.block(s, "prospector.speedBlocks", block)
                || ProspectorFeatures.bonusFor(block) != null
                || Matchers.block(s, "wisdom.xpOreBlocks", block);
    }

    /** True when the block at pos has no placement record (and its chunk history is acceptable). */
    public static boolean isNatural(World world, BlockPos pos) {
        Data data = data(world, pos);
        if (data == null) return true;
        if (data.isPlaced(pos)) return false;
        return !data.legacy || !"DENY".equalsIgnoreCase(Settings.get(world).s("prospector.legacyChunkPolicy"));
    }

    @Nullable
    public static Data data(World world, BlockPos pos) {
        if (world == null || world.isClientSide || CAP == null) return null;
        Chunk chunk = world.getChunkAt(pos);
        return chunk.getCapability(CAP).orElse(null);
    }

    public static void mark(IWorld iworld, BlockPos pos, boolean placed) {
        if (!(iworld instanceof World)) return;
        Data data = data((World) iworld, pos);
        if (data == null) return;
        if (placed) data.placed.add(pos.asLong());
        else data.placed.remove(pos.asLong());
    }

    // ---- events -------------------------------------------------------------------------------------------

    public static void attach(AttachCapabilitiesEvent<Chunk> event) {
        Chunk chunk = event.getObject();
        if (chunk.getLevel() == null || chunk.getLevel().isClientSide) return;
        event.addCapability(KEY, new Provider(chunk));
    }

    /** Entity (player, enderman, fake player...) placed a block. Multi-place (beds, doors) is a subclass. */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = false)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getWorld().isClientSide() || probing) return;
        if (event instanceof BlockEvent.EntityMultiPlaceEvent) {
            for (net.minecraftforge.common.util.BlockSnapshot snap : ((BlockEvent.EntityMultiPlaceEvent) event).getReplacedBlockSnapshots()) {
                BlockState placed = event.getWorld().getBlockState(snap.getPos());
                mark(event.getWorld(), snap.getPos(), isTracked(placed.getBlock()));
            }
            return;
        }
        mark(event.getWorld(), event.getPos(), isTracked(event.getPlacedBlock().getBlock()));
    }

    /** Lava + water making stone/cobble (generators) never counts as natural. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getWorld().isClientSide()) return;
        mark(event.getWorld(), event.getPos(), isTracked(event.getNewState().getBlock()));
    }

    /** Blocks moved by a piston lose their natural status at the destination. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getWorld() instanceof World) || event.getWorld().isClientSide()) return;
        World world = (World) event.getWorld();
        PistonBlockStructureHelper helper = event.getStructureHelper();
        if (helper == null || !helper.resolve()) return;
        Direction dir = event.getPistonMoveType() == PistonEvent.PistonMoveType.EXTEND ? event.getDirection() : event.getDirection().getOpposite();
        List<BlockPos> toPush = new ArrayList<>(helper.getToPush());
        List<BlockPos> marked = new ArrayList<>();
        for (BlockPos from : toPush) {
            if (isTracked(world.getBlockState(from).getBlock())) marked.add(from.relative(dir));
        }
        for (BlockPos from : toPush) mark(world, from, false);
        for (BlockPos to : marked) mark(world, to, true);
    }

    /** Chunks loaded from disk without our data were saved before installation: their history is unknown. */
    @SubscribeEvent
    public static void onChunkLoad(ChunkDataEvent.Load event) {
        IChunk ichunk = event.getChunk();
        Chunk chunk = null;
        if (ichunk instanceof Chunk) chunk = (Chunk) ichunk;
        else if (ichunk instanceof ChunkPrimerWrapper) chunk = ((ChunkPrimerWrapper) ichunk).getWrapped();
        if (chunk == null || CAP == null) return;
        CompoundNBT root = event.getData();
        CompoundNBT level = root.contains("Level") ? root.getCompound("Level") : root;
        boolean hasOurData = level.getCompound("ForgeCaps").contains(KEY.toString());
        if (!hasOurData) chunk.getCapability(CAP).ifPresent(d -> d.legacy = true);
    }

    // ---- capability -----------------------------------------------------------------------------------------

    public static final class Data {
        final LongOpenHashSet placed = new LongOpenHashSet();
        boolean legacy;

        public boolean isPlaced(BlockPos pos) {
            return placed.contains(pos.asLong());
        }

        public boolean isLegacy() {
            return legacy;
        }

        public int size() {
            return placed.size();
        }

        CompoundNBT save(@Nullable Chunk chunk) {
            if (chunk != null) {
                // Drop records whose block is no longer a tracked type (mined out, replaced).
                for (LongIterator it = placed.iterator(); it.hasNext(); ) {
                    BlockPos pos = BlockPos.of(it.nextLong());
                    if (!isTracked(chunk.getBlockState(pos).getBlock())) it.remove();
                }
            }
            CompoundNBT tag = new CompoundNBT();
            tag.putLongArray("placed", placed.toLongArray());
            tag.putBoolean("legacy", legacy);
            return tag;
        }

        void load(CompoundNBT tag) {
            placed.clear();
            for (long l : tag.getLongArray("placed")) placed.add(l);
            legacy = tag.getBoolean("legacy");
        }
    }

    private static final class Provider implements ICapabilitySerializable<CompoundNBT> {
        private final Chunk chunk;
        private final Data data = new Data();
        private final LazyOptional<Data> optional = LazyOptional.of(() -> data);

        Provider(Chunk chunk) {
            this.chunk = chunk;
        }

        @Nonnull
        @Override
        public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
            return cap == CAP ? optional.cast() : LazyOptional.empty();
        }

        @Override
        public CompoundNBT serializeNBT() {
            return data.save(chunk);
        }

        @Override
        public void deserializeNBT(CompoundNBT nbt) {
            data.load(nbt);
        }
    }
}
