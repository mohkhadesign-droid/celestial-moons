package net.ecmoons.mixin;

import net.ecmoons.feature.ArcaneCosts;
import net.ecmoons.feature.ArcaneLock;
import net.ecmoons.feature.DriftFeatures;
import net.ecmoons.feature.HarvestFeatures;
import net.ecmoons.config.Settings;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    @Shadow public Container containerMenu;

    /** Harvest Moon: less hunger exhaustion from every source (server only; exhaustion is server-side). */
    @ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
    private float ecmoons$harvestExhaustion(float amount) {
        return HarvestFeatures.exhaustion((PlayerEntity) (Object) this, amount);
    }

    /** Drift Moon: cheaper jumps (both the walking and sprinting jump exhaustion calls). */
    @ModifyArg(method = "jumpFromGround", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerEntity;causeFoodExhaustion(F)V"))
    private float ecmoons$driftJumpExhaustion(float amount) {
        return DriftFeatures.jumpExhaustion((PlayerEntity) (Object) this, amount);
    }

    /**
     * Arcane Moon: fewer levels consumed by the enchanting table. The discount was locked in when the enchanting
     * screen opened and shown to the client from the same value, so what is displayed is what is charged.
     */
    @ModifyVariable(method = "onEnchantmentPerformed", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int ecmoons$arcaneEnchantCost(int levels) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (self.level.isClientSide || !(containerMenu instanceof EnchantmentContainer)) return levels;
        if (((ArcaneLock) containerMenu).ecmoons$arcane() == 0) return levels;
        return ArcaneCosts.discount(Settings.server(), levels);
    }
}
