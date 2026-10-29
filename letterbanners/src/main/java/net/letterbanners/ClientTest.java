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
            check("stonecutter recipes for every letter of every style (" + cut + ")", cut == 3 * LetterBanners.GLYPHS.length);
            check("crafting recipes for the three blank banners", w.getRecipeManager().byKey(new ResourceLocation(LetterBanners.MOD_ID, "aurum_banner_blank")).isPresent()
                    && w.getRecipeManager().byKey(new ResourceLocation(LetterBanners.MOD_ID, "frost_banner_blank")).isPresent()
                    && w.getRecipeManager().byKey(new ResourceLocation(LetterBanners.MOD_ID, "ember_banner_blank")).isPresent());
            check("all " + LetterBanners.ALL.size() + " banners registered", LetterBanners.ALL.size() == 123);
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
