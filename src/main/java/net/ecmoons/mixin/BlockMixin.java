package net.ecmoons.mixin;

import net.ecmoons.feature.HarvestFeatures;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers which block state is dropping items while a player breaks a block (for the Harvest maturity check). */
@Mixin(Block.class)
public abstract class BlockMixin {
    @Inject(method = "dropResources(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/tileentity/TileEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/item/ItemStack;)V",
            at = @At("HEAD"))
    private static void ecmoons$begin(BlockState state, World world, BlockPos pos, TileEntity te, Entity entity, ItemStack tool, CallbackInfo ci) {
        if (!world.isClientSide) HarvestFeatures.beginDrops(state);
    }

    @Inject(method = "dropResources(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/tileentity/TileEntity;Lnet/minecraft/entity/Entity;Lnet/minecraft/item/ItemStack;)V",
            at = @At("RETURN"))
    private static void ecmoons$end(BlockState state, World world, BlockPos pos, TileEntity te, Entity entity, ItemStack tool, CallbackInfo ci) {
        HarvestFeatures.endDrops();
    }
}
