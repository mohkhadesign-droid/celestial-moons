package net.ecmoons.mixin;

import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.AnvilCostView;
import net.ecmoons.feature.ArcaneCosts;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.AbstractRepairContainer;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.inventory.container.RepairContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IWorldPosCallable;
import net.minecraft.util.IntReferenceHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arcane Moon anvil. Runs at the end of vanilla's result calculation (not when a Forge AnvilUpdateEvent handler
 * took over), after the "Too Expensive" check was made with the ORIGINAL cost:
 * <ol>
 *   <li>material repairs restore +25% durability per material (so fewer materials may be used; the material
 *       type requirement is unchanged), recomputing the unit count and cost exactly as vanilla does;</li>
 *   <li>the level cost is discounted.</li>
 * </ol>
 * The cost lives in the anvil's synced data slot, so the client displays exactly what the server charges.
 */
@Mixin(RepairContainer.class)
public abstract class RepairContainerMixin extends AbstractRepairContainer implements AnvilCostView {
    @Shadow public int repairItemCountCost;
    @Shadow @Final private IntReferenceHolder cost;

    protected RepairContainerMixin(ContainerType<?> type, int id, PlayerInventory inv, IWorldPosCallable access) {
        super(type, id, inv, access);
    }

    @Inject(method = "createResult", at = @At("TAIL"))
    private void ecmoons$arcane(CallbackInfo ci) {
        PlayerEntity player = this.player;
        if (player.level.isClientSide || !Moons.is(player.level, MoonType.ARCANE)) return;
        Settings s = Settings.server();
        if (!s.b("arcane.affectAnvil")) return;
        ItemStack result = this.resultSlots.getItem(0);
        if (result.isEmpty() || cost.get() <= 0) return;

        ItemStack left = this.inputSlots.getItem(0);
        ItemStack right = this.inputSlots.getItem(1);
        if (repairItemCountCost > 0 && !left.isEmpty() && !right.isEmpty() && left.isDamageableItem()
                && left.getItem().isValidRepairItem(left, right)) {
            int per = ArcaneCosts.repairPerUnit(left.getMaxDamage(), s.d("arcane.repairBonusMultiplier"));
            int damage = left.getDamageValue();
            int units = 0;
            while (damage > 0 && units < right.getCount()) {
                damage -= Math.min(damage, per);
                units++;
            }
            if (units > 0 && units <= repairItemCountCost) {
                result.setDamageValue(damage);
                cost.set(cost.get() - (repairItemCountCost - units));
                repairItemCountCost = units;
            }
        }
        cost.set(ArcaneCosts.discount(s, cost.get()));
        this.broadcastChanges();
    }

    @Override
    public int ecmoons$cost() {
        return cost.get();
    }
}
