package net.letterbanners;

import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.state.properties.DoubleBlockHalf;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.gen.settings.DimensionGeneratorSettings;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.util.datafix.codec.DatapackCodec;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

/**
 * Automated client test (CI: -Dletterbanners.clienttest=true under a virtual display). Builds a gallery with the
 * three example signs from both placement modes, places banners through the real item-use path (wall, under a beam,
 * on the floor), checks breaking, drops and recipes, and takes screenshots of the fronts and the backs.
 */
public final class ClientTest {
    private static final String P = "[LB-TEST] ";
    private static final Deque<Runnable> STEPS = new ArrayDeque<>();
    private static int wait, passed, failed, waitTimeout;
    private static BooleanSupplier waitUntil;
    private static boolean requested;
    private static int X, Y = 150, Z;

    private ClientTest() {}

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            LetterBanners.LOGGER.info(P + "PASS: " + what);
        } else {
            failed++;
            LetterBanners.LOGGER.error(P + "FAIL: " + what);
        }
    }

    private static void cmd(String c) {
        MinecraftServer s = Minecraft.getInstance().getSingleplayerServer();
        if (s != null) s.execute(() -> LetterBanners.LOGGER.info(P + "/" + c + " -> " + s.getCommands().performCommand(s.createCommandSourceStack(), c)));
    }

    private static void then(int ticks, Runnable r) {
        STEPS.add(() -> wait = ticks);
        STEPS.add(r);
    }

    private static void waitFor(int timeout, BooleanSupplier c) {
        STEPS.add(() -> {
            waitUntil = c;
            waitTimeout = timeout;
        });
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        ScreenShotHelper.grab(mc.gameDirectory, name + ".png", mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getMainRenderTarget(),
                m -> LetterBanners.LOGGER.info(P + "screenshot " + name));
    }

    private static ServerWorld sw() {
        return Minecraft.getInstance().getSingleplayerServer().overworld();
    }

    private static String id(String style, String g) {
        return "letterbanners:" + style + "_banner_" + g;
    }

    private static boolean isMissing(net.minecraft.client.renderer.texture.TextureAtlasSprite s) {
        return s == null || s.getName().getPath().equals("missingno");
    }

    /** market, gap, S H O P S: from the front (looking south) the first item is at the highest x. */
    private static void shopRow(String style, int right, int upperY, int z, boolean wall) {
        String[] items = {"market", null, "s", "h", "o", "p", "s"};
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null) continue;
            String b = id(style, items[i]);
            cmd("setblock " + (right - i) + " " + upperY + " " + z + " " + b + "[facing=north,half=upper,wall=" + wall + "]");
            cmd("setblock " + (right - i) + " " + (upperY - 1) + " " + z + " " + b + "[facing=north,half=lower,wall=" + wall + "]");
        }
    }

    private static String glyph(char c) {
        return Character.isLetterOrDigit(c) ? String.valueOf(Character.toLowerCase(c)) : c == '&' ? "amp" : c == '!' ? "excl" : c == '?' ? "question"
                : c == '-' ? "dash" : "blank";
    }

    /** A word of banners hanging from a beam at height top (upper half at top-1), facing north, starting at x. */
    private static void hangWord(String style, String word, int x, int top, int z) {
        cmd("fill " + (x - 1) + " " + top + " " + z + " " + (x + word.length()) + " " + top + " " + z + " minecraft:dark_oak_planks");
        for (int i = 0; i < word.length(); i++) {
            String b = id(style, glyph(word.charAt(i)));
            int bx = x + word.length() - 1 - i; // seen from the front (looking south) +x is on the left
            cmd("setblock " + bx + " " + (top - 1) + " " + z + " " + b + "[facing=north,half=upper,wall=false]");
            cmd("setblock " + bx + " " + (top - 2) + " " + z + " " + b + "[facing=north,half=lower,wall=false]");
        }
    }

    /** Put the camera somewhere and keep it there: flying on both the client and the integrated server. */
    private static void view(double x, double y, double z, float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        mc.player.abilities.mayfly = true;
        mc.player.abilities.flying = true;
        mc.player.onUpdateAbilities();
        mc.getSingleplayerServer().execute(() -> {
            net.minecraft.entity.player.ServerPlayerEntity sp = mc.getSingleplayerServer().getPlayerList().getPlayers().get(0);
            sp.abilities.mayfly = true;
            sp.abilities.flying = true;
            sp.onUpdateAbilities();
        });
        cmd("tp @a " + x + " " + y + " " + z + " " + yaw + " " + pitch);
    }

    private static void plan() {
        then(40, () -> {
            ClientPlayerEntity p = Minecraft.getInstance().player;
            X = (int) Math.floor(p.getX());
            Z = (int) Math.floor(p.getZ());
            cmd("time set 6000");
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doWeatherCycle false");
            cmd("weather clear");
            p.abilities.flying = true;
            p.onUpdateAbilities();
            view(X + 0.5, Y + 6, Z - 8, 0, 0);
        });
        then(60, () -> {
            cmd("fill " + (X - 14) + " " + (Y - 1) + " " + (Z - 12) + " " + (X + 16) + " " + (Y - 1) + " " + (Z + 30) + " minecraft:stone_bricks");
            cmd("fill " + (X - 14) + " " + Y + " " + (Z - 12) + " " + (X + 16) + " " + (Y + 14) + " " + (Z + 30) + " minecraft:air");
            // gallery: the three signs from the pictures, two rows of five, hanging from beams
            hangWord("aurum", "AURUM", X - 11, Y + 12, Z + 10);
            hangWord("aurum", "FORGE", X - 11, Y + 8, Z + 10);
            hangWord("frost", "FROST", X - 2, Y + 12, Z + 10);
            hangWord("frost", "SPELL", X - 2, Y + 8, Z + 10);
            hangWord("ember", "EMBER", X + 7, Y + 8 + 4, Z + 10);
            hangWord("ember", "GEARS", X + 7, Y + 8, Z + 10);
            // a wall for the wall-mounted test, and the extra characters on it
            cmd("fill " + (X - 11) + " " + Y + " " + (Z + 24) + " " + (X + 12) + " " + (Y + 6) + " " + (Z + 24) + " minecraft:polished_blackstone_bricks");
        });
        then(40, () -> {
            view(X + 0.5, Y + 7.5, Z - 9, 0, 0);
            Minecraft.getInstance().options.hideGui = true;
        });
        then(60, () -> shot("01_gallery_front"));
        then(2, () -> view(X - 8.5, Y + 8, Z + 3, 0, 0));
        then(30, () -> shot("02_aurum_front_close"));
        then(2, () -> view(X + 0.5, Y + 8, Z + 3, 0, 0));
        then(30, () -> shot("03_frost_front_close"));
        then(2, () -> view(X + 9.5, Y + 8, Z + 3, 0, 0));
        then(30, () -> shot("04_ember_front_close"));
        // backs: the same pictures, readable (so words read in reverse order from behind)
        then(2, () -> view(X + 0.5, Y + 7.5, Z + 22, 180, 0));
        then(40, () -> shot("05_gallery_back"));
        then(2, () -> view(X - 8.5, Y + 8, Z + 17, 180, 0));
        then(30, () -> shot("06_aurum_back_close"));
        then(2, () -> view(X + 0.5, Y + 8, Z + 17, 180, 0));
        then(30, () -> shot("07_frost_back_close"));
        then(2, () -> view(X + 9.5, Y + 8, Z + 17, 180, 0));
        then(30, () -> shot("08_ember_back_close"));
        then(2, () -> view(X + 5.5, Y + 9, Z + 8, -60, 25));
        then(30, () -> shot("09_side_angle"));
        // real placement through the item: on the wall, under the beam, on the floor
        then(2, () -> {
            Minecraft.getInstance().options.hideGui = false;
            cmd("gamemode survival @a");
            view(X + 0.5, Y, Z + 21.5, 180, 0);
        });
        then(20, () -> {
            ClientPlayerEntity p = Minecraft.getInstance().player;
            p.inventory.selected = 0;
            cmd("replaceitem entity @p weapon.mainhand " + id("ember", "h") + " 16");
        });
        then(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            BlockPos wall = new BlockPos(X, Y + 3, Z + 24);
            mc.gameMode.useItemOn(mc.player, mc.level, Hand.MAIN_HAND,
                    new BlockRayTraceResult(new Vector3d(X + 0.5, Y + 3.5, Z + 24), Direction.NORTH, wall, false));
        });
        then(10, () -> {
            ServerWorld w = sw();
            BlockState up = w.getBlockState(new BlockPos(X, Y + 3, Z + 23)), lo = w.getBlockState(new BlockPos(X, Y + 2, Z + 23));
            check("wall placement: top half on the wall, facing away from it (" + up + ")", up.getBlock() instanceof LetterBannerBlock
                    && up.getValue(LetterBannerBlock.WALL) && up.getValue(LetterBannerBlock.FACING) == Direction.NORTH
                    && up.getValue(LetterBannerBlock.HALF) == DoubleBlockHalf.UPPER);
            check("wall placement: bottom half below it (" + lo + ")", lo.getBlock() == up.getBlock() && lo.getValue(LetterBannerBlock.HALF) == DoubleBlockHalf.LOWER);
            view(X - 2.5, Y + 5, Z + 12.5, 180, -30); // near the SPELL beam
        });
        then(2, () -> {
            Minecraft mc = Minecraft.getInstance();
            BlockPos beam = new BlockPos(X - 3, Y + 8, Z + 10); // the beam's end, just west of the word
            mc.gameMode.useItemOn(mc.player, mc.level, Hand.MAIN_HAND,
                    new BlockRayTraceResult(new Vector3d(X - 2.5, Y + 8, Z + 10.5), Direction.DOWN, beam, false));
        });
        then(5, () -> view(X + 3.5, Y, Z + 22.5, 180, 30));
        then(5, () -> {
            Minecraft mc = Minecraft.getInstance();
            BlockPos floor = new BlockPos(X + 3, Y - 1, Z + 20);
            mc.gameMode.useItemOn(mc.player, mc.level, Hand.MAIN_HAND,
                    new BlockRayTraceResult(new Vector3d(X + 3.5, Y, Z + 20.5), Direction.UP, floor, false));
        });
        then(10, () -> {
            ServerWorld w = sw();
            BlockState hung = w.getBlockState(new BlockPos(X - 3, Y + 7, Z + 10));
            check("hung under a beam: top half, not on a wall (" + hung + ")", hung.getBlock() instanceof LetterBannerBlock
                    && !hung.getValue(LetterBannerBlock.WALL) && hung.getValue(LetterBannerBlock.HALF) == DoubleBlockHalf.UPPER
                    && w.getBlockState(new BlockPos(X - 3, Y + 6, Z + 10)).getBlock() == hung.getBlock());
            BlockState lo = w.getBlockState(new BlockPos(X + 3, Y, Z + 20)), up = w.getBlockState(new BlockPos(X + 3, Y + 1, Z + 20));
            check("on the floor: bottom half on the ground, top above it, facing the player", lo.getBlock() instanceof LetterBannerBlock
                    && lo.getValue(LetterBannerBlock.HALF) == DoubleBlockHalf.LOWER && up.getBlock() == lo.getBlock()
                    && up.getValue(LetterBannerBlock.HALF) == DoubleBlockHalf.UPPER && lo.getValue(LetterBannerBlock.FACING) == Direction.SOUTH);
            check("three banners used from the stack of 16", Minecraft.getInstance().player.getMainHandItem().getCount() == 13);
            // survival break of the top half: both halves go, exactly one banner drops
            Minecraft.getInstance().getSingleplayerServer().execute(() -> {
                w.destroyBlock(new BlockPos(X, Y + 3, Z + 23), true, Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayers().get(0));
            });
        });
        then(5, () -> {
            ServerWorld w = sw();
            check("breaking the top half removes the bottom half too", w.isEmptyBlock(new BlockPos(X, Y + 3, Z + 23)) && w.isEmptyBlock(new BlockPos(X, Y + 2, Z + 23)));
            int n = 0;
            for (ItemEntity e : w.getEntitiesOfClass(ItemEntity.class, new AxisAlignedBB(new BlockPos(X, Y + 2, Z + 23)).inflate(3))) {
                ItemStack st = e.getItem();
                if (ForgeRegistries.ITEMS.getKey(st.getItem()).toString().equals(id("ember", "h"))) n += st.getCount();
            }
            check("exactly one banner drops (" + n + ")", n == 1);
            // recipes
            int cut = 0;
            for (Object r : w.getRecipeManager().getAllRecipesFor(IRecipeType.STONECUTTING)) {
                net.minecraft.item.crafting.IRecipe<?> rr = (net.minecraft.item.crafting.IRecipe<?>) r;
                if (rr.getId().getNamespace().equals(LetterBanners.MOD_ID)) cut++;
            }
            check("stonecutter recipes for every banner of every style (" + cut + ")", cut == LetterBanners.ALL.size());
            check("crafting recipes for the three blank banners", w.getRecipeManager().byKey(new ResourceLocation(LetterBanners.MOD_ID, "aurum_banner_blank")).isPresent()
                    && w.getRecipeManager().byKey(new ResourceLocation(LetterBanners.MOD_ID, "frost_banner_blank")).isPresent()
                    && w.getRecipeManager().byKey(new ResourceLocation(LetterBanners.MOD_ID, "ember_banner_blank")).isPresent());
            check("all " + LetterBanners.ALL.size() + " banners registered (3 x 41 + 3 x 42)", LetterBanners.ALL.size() == 249);
            boolean shopSet = true;
            for (String st : LetterBanners.SHOP_STYLES) {
                for (String g : new String[]{"a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t",
                        "u", "v", "w", "x", "y", "z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "blank", "market"}) {
                    shopSet &= ForgeRegistries.BLOCKS.containsKey(new ResourceLocation(id(st, g)));
                }
                shopSet &= w.getRecipeManager().byKey(new ResourceLocation(LetterBanners.MOD_ID, st + "_banner_blank")).isPresent();
            }
            check("royal, crimson and azure each have A-Z, 0-9, blank and market, and a blank-banner recipe", shopSet);
            // every character on the wall, to look at in the screenshot
            String[] rows = {"ABCDEFGHIJKLM", "NOPQRSTUVWXYZ", "0123456789&!?-"};
            String[] styles = LetterBanners.STYLES;
            int x0 = X - 40;
            cmd("fill " + (x0 - 1) + " " + (Y - 1) + " " + (Z + 40) + " " + (x0 + 46) + " " + (Y + 9) + " " + (Z + 40) + " minecraft:stone_bricks");
            cmd("fill " + (x0 - 1) + " " + (Y - 1) + " " + (Z + 30) + " " + (x0 + 46) + " " + (Y - 1) + " " + (Z + 39) + " minecraft:stone_bricks");
            for (int s = 0; s < 3; s++) {
                String row = rows[s];
                for (int i = 0; i < row.length(); i++) {
                    String b = id(styles[(i + s) % 3], glyph(row.charAt(i)));
                    int x = x0 + s * 15 + (row.length() - 1 - i), y = Y + 2;
                    cmd("setblock " + x + " " + (y + 1) + " " + (Z + 39) + " " + b + "[facing=north,half=upper,wall=true]");
                    cmd("setblock " + x + " " + y + " " + (Z + 39) + " " + b + "[facing=north,half=lower,wall=true]");
                }
            }
            cmd("gamemode creative @a");
            Minecraft.getInstance().options.hideGui = true;
        });
        then(10, () -> view(X - 40 + 22.5, Y + 2.5, Z + 22, 0, 0));
        then(60, () -> shot("10_all_characters_on_wall"));
        then(2, () -> view(X - 40 + 6.5, Y + 2.5, Z + 32, 0, 0));
        then(30, () -> shot("11_wall_close"));
        // ---- shop banners (royal, crimson, azure): every model and texture present, the SHOPS gallery, placement
        then(2, () -> {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.client.renderer.model.IBakedModel missing = mc.getModelManager().getMissingModel();
            java.util.Random rnd = new java.util.Random(1);
            int states = 0, items = 0, bad = 0;
            for (net.minecraftforge.fml.RegistryObject<net.minecraft.block.Block> b : LetterBanners.ALL) {
                for (BlockState st : b.get().getStateDefinition().getPossibleStates()) {
                    states++;
                    net.minecraft.client.renderer.model.IBakedModel m = mc.getBlockRenderer().getBlockModel(st);
                    boolean ok = m != missing && !isMissing(m.getParticleIcon());
                    java.util.List<net.minecraft.client.renderer.model.BakedQuad> quads = new java.util.ArrayList<>(m.getQuads(st, null, rnd));
                    for (Direction d : Direction.values()) quads.addAll(m.getQuads(st, d, rnd));
                    for (net.minecraft.client.renderer.model.BakedQuad q : quads) ok &= !isMissing(q.getSprite());
                    ok &= !quads.isEmpty();
                    if (!ok) {
                        bad++;
                        LetterBanners.LOGGER.error(P + "missing model or texture: " + st);
                    }
                }
                items++;
                net.minecraft.client.renderer.model.IBakedModel im = mc.getItemRenderer().getItemModelShaper().getItemModel(new ItemStack(b.get()));
                if (im == mc.getItemRenderer().getItemModelShaper().getModelManager().getMissingModel() || isMissing(im.getParticleIcon())) {
                    bad++;
                    LetterBanners.LOGGER.error(P + "missing item model: " + b.getId());
                }
            }
            check("every block state and item has its model and textures (" + states + " states, " + items + " items, " + bad + " missing)", bad == 0);
            // the gallery: market + SHOPS in each colour, hanging from a beam; and the same on a wall
            cmd("fill " + (X - 24) + " " + (Y - 1) + " " + (Z + 50) + " " + (X + 24) + " " + (Y - 1) + " " + (Z + 90) + " minecraft:stone_bricks");
            cmd("fill " + (X - 24) + " " + Y + " " + (Z + 50) + " " + (X + 24) + " " + (Y + 14) + " " + (Z + 89) + " minecraft:air");
            cmd("fill " + (X - 24) + " " + Y + " " + (Z + 90) + " " + (X + 24) + " " + (Y + 12) + " " + (Z + 90) + " minecraft:polished_blackstone_bricks");
            String[] st = LetterBanners.SHOP_STYLES;
            for (int k = 0; k < 3; k++) {
                int right = X + 19 - k * 15; // seen from the front (looking south) +x is on the left: the first item goes at the highest x
                cmd("fill " + (right - 7) + " " + (Y + 10) + " " + (Z + 60) + " " + (right + 1) + " " + (Y + 10) + " " + (Z + 60) + " minecraft:dark_oak_planks");
                shopRow(st[k], right, Y + 9, Z + 60, false);
                shopRow(st[k], right, Y + 4, Z + 89, true);
            }
            Minecraft.getInstance().options.hideGui = true;
            cmd("gamemode creative @a");
        });
        then(20, () -> view(X + 0.5, Y + 6.5, Z + 33, 0, 0));
        then(50, () -> shot("12_shop_gallery_front"));
        for (int k = 0; k < 3; k++) {
            final int right = X + 19 - k * 15;
            final String name = LetterBanners.SHOP_STYLES[k];
            then(2, () -> view(right - 2.5, Y + 7.5, Z + 52.5, 0, 5));
            then(30, () -> shot("13_shop_" + name + "_front"));
        }
        then(2, () -> view(X + 0.5, Y + 6.5, Z + 80, 180, 0));
        then(50, () -> shot("14_shop_gallery_back"));
        then(2, () -> view(X + 19 - 2.5, Y + 7.5, Z + 67.5, 180, 5));
        then(30, () -> shot("15_shop_royal_back"));
        then(2, () -> view(X + 22.5, Y + 8.5, Z + 56, 40, 15));
        then(30, () -> shot("16_shop_side_angle"));
        then(2, () -> view(X + 19 - 2.5, Y + 3.5, Z + 83, 0, 5));
        then(30, () -> shot("17_shop_wall_royal"));
        then(2, () -> view(X + 4 - 2.5, Y + 3.5, Z + 83, 0, 5));
        then(30, () -> shot("18_shop_wall_crimson"));
        // real placement of a shop banner: on the wall and under a beam it works, on the floor it is refused
        then(2, () -> {
            Minecraft.getInstance().options.hideGui = false;
            cmd("gamemode survival @a");
            view(X - 18.5, Y, Z + 86.5, 0, 0);
        });
        then(20, () -> {
            Minecraft.getInstance().player.inventory.selected = 0;
            cmd("replaceitem entity @p weapon.mainhand " + id("crimson", "market") + " 8");
        });
        then(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.gameMode.useItemOn(mc.player, mc.level, Hand.MAIN_HAND,
                    new BlockRayTraceResult(new Vector3d(X - 18.5, Y + 3.5, Z + 90), Direction.NORTH, new BlockPos(X - 18, Y + 3, Z + 90), false));
            mc.gameMode.useItemOn(mc.player, mc.level, Hand.MAIN_HAND,
                    new BlockRayTraceResult(new Vector3d(X - 20.5, Y, Z + 86.5), Direction.UP, new BlockPos(X - 21, Y - 1, Z + 86), false));
        });
        then(10, () -> {
            ServerWorld w = sw();
            BlockState up = w.getBlockState(new BlockPos(X - 18, Y + 3, Z + 89)), lo = w.getBlockState(new BlockPos(X - 18, Y + 2, Z + 89));
            check("shop banner on a wall: both halves, facing out (" + up + ")", up.getBlock().getRegistryName().toString().equals(id("crimson", "market"))
                    && up.getValue(LetterBannerBlock.WALL) && up.getValue(LetterBannerBlock.HALF) == DoubleBlockHalf.UPPER && lo.getBlock() == up.getBlock());
            check("shop banner on the floor is refused (it only hangs)", w.isEmptyBlock(new BlockPos(X - 21, Y, Z + 86)) && w.isEmptyBlock(new BlockPos(X - 21, Y + 1, Z + 86)));
            check("one shop banner used", Minecraft.getInstance().player.getMainHandItem().getCount() == 7);
        });
        then(5, () -> {
            LetterBanners.LOGGER.info(P + "LBTEST COMPLETE passed=" + passed + " failed=" + failed);
            Minecraft.getInstance().stop();
        });
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (!requested) {
            if (mc.screen instanceof MainMenuScreen) {
                requested = true;
                DynamicRegistries.Impl reg = DynamicRegistries.builtin();
                WorldSettings ws = new WorldSettings("lb-test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), DatapackCodec.DEFAULT);
                mc.createLevel("lb-test", ws, reg, DimensionGeneratorSettings.demoSettings(reg));
                plan();
            }
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (waitUntil != null) {
            if (waitUntil.getAsBoolean() || --waitTimeout <= 0) waitUntil = null;
            else return;
        }
        if (wait-- > 0) return;
        Runnable r = STEPS.poll();
        if (r != null) {
            try {
                r.run();
            } catch (Throwable t) {
                failed++;
                LetterBanners.LOGGER.error(P + "FAIL: exception", t);
            }
        }
    }
}
