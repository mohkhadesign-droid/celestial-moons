package net.ecmoons.mixin;

import net.minecraft.entity.projectile.AbstractArrowEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Shell Moon: an intercepted arrow or trident is dropped as exactly the item it would have given back. */
@Mixin(AbstractArrowEntity.class)
public interface AbstractArrowEntityAccess {
    @Invoker("getPickupItem")
    ItemStack ecmoons$getPickupItem();
}
