package net.ecmoons.mixin.client;

import net.ecmoons.config.Settings;
import net.ecmoons.feature.ArcaneCosts;
import net.ecmoons.feature.ArcaneLock;
import net.minecraft.client.gui.screen.EnchantmentScreen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Arcane Moon: the enchanting tooltip's "N Enchantment Levels" line shows the discounted consumption, computed
 * with the same function and the same server-decided flag that the server uses to charge it.
 */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantmentScreenMixin extends ContainerScreen<EnchantmentContainer> {
    protected EnchantmentScreenMixin(EnchantmentContainer container, PlayerInventory inventory, ITextComponent title) {
        super(container, inventory, title);
    }

    @Redirect(method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;IIF)V",
            at = @At(value = "NEW", target = "(Ljava/lang/String;[Ljava/lang/Object;)Lnet/minecraft/util/text/TranslationTextComponent;"),
            require = 0)
    private TranslationTextComponent ecmoons$arcaneLevelText(String key, Object[] args) {
        if ("container.enchant.level.many".equals(key) && args.length == 1 && args[0] instanceof Integer
                && ((ArcaneLock) this.menu).ecmoons$arcane() == 1) {
            int levels = ArcaneCosts.discount(Settings.client(), (Integer) args[0]);
            return levels == 1 ? new TranslationTextComponent("container.enchant.level.one")
                    : new TranslationTextComponent(key, levels);
        }
        return new TranslationTextComponent(key, args);
    }
}
