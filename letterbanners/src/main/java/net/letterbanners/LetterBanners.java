package net.letterbanners;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Letter Banners: two-block-tall hanging banners with one letter each. Styles: aurum, frost, ember, and the shop
 * collection (royal, crimson, azure) which also has a market banner. Every style has A-Z, 0-9, &amp; ! ? - and a blank
 * banner. Craft a blank, then pick the letter in a stonecutter.
 */
@Mod(LetterBanners.MOD_ID)
public class LetterBanners {
    public static final String MOD_ID = "letterbanners";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final String[] STYLES = {"aurum", "frost", "ember"};
    /** The shop collection (royal blue, crimson, azure): the same characters plus the market banner. */
    public static final String[] SHOP_STYLES = {"royal", "crimson", "azure"};
    public static final String[] GLYPHS = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r",
            "s", "t", "u", "v", "w", "x", "y", "z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "amp", "excl", "question",
            "dash", "blank"};

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final List<RegistryObject<Block>> ALL = new ArrayList<>();

    public static final ItemGroup TAB = new ItemGroup(MOD_ID) {
        @Override
        public ItemStack makeIcon() {
            return new ItemStack(ALL.get(0).get());
        }
    };

    static {
        for (String style : STYLES) {
            MaterialColor colour = style.equals("aurum") ? MaterialColor.COLOR_YELLOW : style.equals("frost") ? MaterialColor.COLOR_BLUE
                    : MaterialColor.COLOR_RED;
            for (String g : GLYPHS) register(style, g, colour, true);
        }
        for (String style : SHOP_STYLES) {
            MaterialColor colour = style.equals("royal") ? MaterialColor.COLOR_BLUE : style.equals("crimson") ? MaterialColor.COLOR_RED
                    : MaterialColor.COLOR_LIGHT_BLUE;
            for (String g : GLYPHS) register(style, g, colour, false);
            register(style, "market", colour, false);
        }
    }

    /** One block (and its item) per design: style_banner_glyph. Shop banners hang below their rod with a tassel, so
     * they can be put on a wall or hung under a block, but not stood on the floor (the tassel would sink into it). */
    private static void register(String style, String g, MaterialColor colour, boolean floor) {
        String name = style + "_banner_" + g;
        RegistryObject<Block> b = BLOCKS.register(name, () -> new LetterBannerBlock(Block.Properties.of(Material.WOOL, colour)
                .noCollission().strength(1.0F).sound(SoundType.WOOL).noOcclusion(), floor));
        ITEMS.register(name, () -> new BlockItem(b.get(), new Item.Properties().tab(TAB).stacksTo(16)));
        ALL.add(b);
    }

    public LetterBanners() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientInit.init(modBus));
    }
}
