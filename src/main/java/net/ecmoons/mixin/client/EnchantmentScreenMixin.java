package net.ecmoons.mixin.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.ecmoons.client.ClientState;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.ArcaneCosts;
import net.ecmoons.feature.ArcaneLock;
import net.minecraft.client.gui.screen.EnchantmentScreen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.ITextProperties;
import net.minecraft.util.text.TranslationTextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.List;

/**
 * Arcane Moon: the enchanting tooltip's "N Enchantment Levels" line shows the discounted consumption, computed
 * with the same function and the same server-decided flag that the server uses to charge it. The finished
 * tooltip list is adjusted right before vanilla draws it; nothing else in the screen is touched.
 */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantmentScreenMixin extends ContainerScreen<EnchantmentContainer> {
    private static final String LEVEL_MANY = "container.enchant.level.many";

    protected EnchantmentScreenMixin(EnchantmentContainer container, PlayerInventory inventory, ITextComponent title) {
        super(container, inventory, title);
    }

    @ModifyArg(method = "render(Lcom/mojang/blaze3d/matrix/MatrixStack;IIF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/EnchantmentScreen;renderComponentTooltip(Lcom/mojang/blaze3d/matrix/MatrixStack;Ljava/util/List;II)V"),
            index = 1, require = 0)
    private List<? extends ITextProperties> ecmoons$arcaneTooltip(List<? extends ITextProperties> lines) {
        boolean arcane = ((ArcaneLock) this.menu).ecmoons$arcane() == 1;
        @SuppressWarnings("unchecked")
        List<ITextProperties> list = (List<ITextProperties>) lines;
        for (int i = 0; i < list.size(); i++) {
            ITextProperties line = list.get(i);
            if (!(line instanceof TranslationTextComponent)) continue;
            TranslationTextComponent text = (TranslationTextComponent) line;
            Object[] args = text.getArgs();
            if (!LEVEL_MANY.equals(text.getKey()) || args.length != 1 || !(args[0] instanceof Integer)) continue;
            int levels = (Integer) args[0];
            if (arcane) {
                levels = ArcaneCosts.discount(Settings.client(), levels);
                TranslationTextComponent replacement = levels == 1
                        ? new TranslationTextComponent("container.enchant.level.one")
                        : new TranslationTextComponent(LEVEL_MANY, levels);
                replacement.setStyle(text.getStyle());
                list.set(i, replacement);
            }
            ClientState.lastTooltipLevels = levels;
        }
        return lines;
    }
}
