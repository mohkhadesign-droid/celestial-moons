package net.ecmoons.mixin;

import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.ArcaneLock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.util.IWorldPosCallable;
import net.minecraft.util.IntReferenceHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arcane Moon enchanting: the server decides once, when the table is opened, whether the discount applies, and
 * sends that decision to the client as a normal container data slot. The client tooltip and the server charge
 * both read this same value, so a moon change while the screen is open can never cause a mismatch.
 */
@Mixin(EnchantmentContainer.class)
public abstract class EnchantmentContainerMixin extends Container implements ArcaneLock {
    @Unique private final IntReferenceHolder ecmoons$arcaneSlot = IntReferenceHolder.standalone();

    protected EnchantmentContainerMixin(ContainerType<?> type, int id) {
        super(type, id);
    }

    @Inject(method = "<init>(ILnet/minecraft/entity/player/PlayerInventory;Lnet/minecraft/util/IWorldPosCallable;)V", at = @At("RETURN"))
    private void ecmoons$lockArcane(int id, PlayerInventory inventory, IWorldPosCallable access, CallbackInfo ci) {
        boolean server = !inventory.player.level.isClientSide;
        boolean arcane = server && Moons.is(inventory.player.level, MoonType.ARCANE) && Settings.server().b("arcane.affectEnchanting");
        ecmoons$arcaneSlot.set(arcane ? 1 : 0);
        this.addDataSlot(ecmoons$arcaneSlot);
    }

    @Override
    public int ecmoons$arcane() {
        return ecmoons$arcaneSlot.get();
    }
}
