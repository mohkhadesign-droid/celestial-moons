package net.letterbanners;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.BooleanProperty;
import net.minecraft.state.EnumProperty;
import net.minecraft.state.StateContainer;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.state.properties.DoubleBlockHalf;
import net.minecraft.pathfinding.PathType;
import net.minecraft.util.Direction;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.IWorld;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * A two-block-tall letter banner. The front faces {@code facing}; the back shows the same picture (readable from
 * behind). wall=true: placed on the side of a block, it hangs flat against that wall. wall=false: hung under a block
 * (or stood on the floor) it hangs in the middle of the block from two short chains, like the gate signs.
 * Breaking either half removes both and drops one banner (creative: none), like a door.
 */
public class LetterBannerBlock extends HorizontalBlock {
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final BooleanProperty WALL = BooleanProperty.create("wall");
    private static final VoxelShape[] WALL_UPPER = new VoxelShape[4], WALL_LOWER = new VoxelShape[4], FREE_UPPER = new VoxelShape[4],
            FREE_LOWER = new VoxelShape[4];

    static {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int i = d.get2DDataValue();
            WALL_UPPER[i] = rotated(d, 1, 0, 14, 15, 16, 16);
            WALL_LOWER[i] = rotated(d, 1, 0, 14.5, 15, 16, 16);
            FREE_UPPER[i] = rotated(d, 1, 0, 7, 15, 16, 9);
            FREE_LOWER[i] = rotated(d, 1, 0, 7, 15, 16, 9);
        }
    }

    /** A box given for a banner facing north, turned to face d. */
    private static VoxelShape rotated(Direction d, double x0, double y0, double z0, double x1, double y1, double z1) {
        switch (d) {
            case SOUTH: return Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0);
            case EAST: return Block.box(16 - z1, y0, x0, 16 - z0, y1, x1);
            case WEST: return Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0);
            default: return Block.box(x0, y0, z0, x1, y1, z1);
        }
    }

    public LetterBannerBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.UPPER).setValue(WALL, false));
    }

    @Override
    protected void createBlockStateDefinition(StateContainer.Builder<Block, BlockState> b) {
        b.add(FACING, HALF, WALL);
    }

    @Override
    public VoxelShape getShape(BlockState state, IBlockReader world, BlockPos pos, ISelectionContext ctx) {
        int i = state.getValue(FACING).get2DDataValue();
        boolean upper = state.getValue(HALF) == DoubleBlockHalf.UPPER;
        return state.getValue(WALL) ? (upper ? WALL_UPPER[i] : WALL_LOWER[i]) : (upper ? FREE_UPPER[i] : FREE_LOWER[i]);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockItemUseContext ctx) {
        World world = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        Direction face = ctx.getClickedFace();
        if (face == Direction.UP) { // stood on the floor: this block is the bottom half
            if (pos.getY() >= world.getMaxBuildHeight() - 1 || !world.getBlockState(pos.above()).canBeReplaced(ctx)) return null;
            return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite()).setValue(HALF, DoubleBlockHalf.LOWER)
                    .setValue(WALL, false);
        }
        // on a wall or under a block: this block is the top half and the banner hangs into the block below
        if (pos.getY() <= 0 || !world.getBlockState(pos.below()).canBeReplaced(ctx)) return null;
        boolean wall = face.getAxis().isHorizontal();
        Direction facing = wall ? face : ctx.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing).setValue(HALF, DoubleBlockHalf.UPPER).setValue(WALL, wall);
    }

    @Override
    public void setPlacedBy(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        boolean upper = state.getValue(HALF) == DoubleBlockHalf.UPPER;
        world.setBlock(upper ? pos.below() : pos.above(), state.setValue(HALF, upper ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER), 3);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState other, IWorld world, BlockPos pos, BlockPos otherPos) {
        DoubleBlockHalf half = state.getValue(HALF);
        Direction partner = half == DoubleBlockHalf.UPPER ? Direction.DOWN : Direction.UP;
        if (dir == partner && (other.getBlock() != this || other.getValue(HALF) == half)) return Blocks.AIR.defaultBlockState();
        return state;
    }

    @Override
    public void playerWillDestroy(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        // creative: breaking the top half must not let the bottom half drop an item
        if (!world.isClientSide && player.isCreative() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos below = pos.below();
            BlockState b = world.getBlockState(below);
            if (b.getBlock() == this && b.getValue(HALF) == DoubleBlockHalf.LOWER) {
                world.setBlock(below, Blocks.AIR.defaultBlockState(), 35);
                world.levelEvent(player, 2001, below, Block.getId(b));
            }
        }
        super.playerWillDestroy(world, pos, state, player);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public boolean isPathfindable(BlockState state, IBlockReader world, BlockPos pos, PathType type) {
        return true;
    }
}
