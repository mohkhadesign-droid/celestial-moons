package net.ecmoons.client;

import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.feature.FrostFeatures;
import net.ecmoons.net.FrostStatePacket;
import net.ecmoons.selftest.RealPlayerChecks;
import net.ecmoons.selftest.WarPlayerChecks;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.EnchantmentScreen;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.inventory.container.EnchantmentContainer;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.registry.DynamicRegistries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.gen.settings.DimensionGeneratorSettings;
import net.minecraft.util.datafix.codec.DatapackCodec;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

/**
 * Automated client test (CI runs it under a virtual X display with -Decmoons.clienttest=true).
 * Creates a singleplayer world, then drives the integrated server with commands and checks what only a real
 * client can show: the moon designs in the sky (screenshots), Mist fog fade and dimension-change cleanup,
 * the Frost slowdown and overlay, and Drift jump height and fall damage. Results are logged as
 * "[ECMOONS-CLIENTTEST] PASS/FAIL ..." and screenshots are written to run-client/screenshots.
 */
public final class ClientSelfTest {
    private static final String P = "[ECMOONS-CLIENTTEST] ";
    private static final Deque<Runnable> STEPS = new ArrayDeque<>();
    private static int wait;
    private static BooleanSupplier waitUntil;
    private static int waitUntilTimeout;
    private static boolean worldRequested;
    private static int passed, failed;

    // measurements
    private static double jumpStartY, jumpMaxY, vanillaJump, driftJump;
    private static float healthBefore, vanillaFallDamage, driftFallDamage;
    private static int px, pz, platformY;

    private ClientSelfTest() {}

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            EcMoons.LOGGER.info(P + "PASS: " + what);
        } else {
            failed++;
            EcMoons.LOGGER.error(P + "FAIL: " + what);
        }
    }

    private static void cmd(String command) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            int r = server.getCommands().performCommand(server.createCommandSourceStack(), command);
            EcMoons.LOGGER.info(P + "/" + command + " -> " + r);
        });
    }

    /** Wait delayTicks, then run r. */
    private static void onServer(java.util.function.Consumer<MinecraftServer> action) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) server.execute(() -> {
            try {
                action.accept(server);
            } catch (Throwable t) {
                RealPlayerChecks.FAILED.incrementAndGet();
                EcMoons.LOGGER.error(P + "FAIL: exception in server-side check", t);
            }
        });
    }

    private static void then(int delayTicks, Runnable r) {
        STEPS.add(() -> wait = delayTicks);
        STEPS.add(r);
    }

    private static void waitFor(int timeoutTicks, BooleanSupplier condition) {
        STEPS.add(() -> {
            waitUntil = condition;
            waitUntilTimeout = timeoutTicks;
        });
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        ScreenShotHelper.grab(mc.gameDirectory, name + ".png", mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.getMainRenderTarget(), msg -> EcMoons.LOGGER.info(P + "screenshot " + name + ": " + msg.getString()));
    }

    private static ClientPlayerEntity player() {
        return Minecraft.getInstance().player;
    }

    private static void lookUp() {
        ClientPlayerEntity p = player();
        p.xRot = -90F;
        p.xRotO = -90F;
        p.yRot = -90F; // facing east: the moon's texture is upright as seen at moonrise/moonset
        p.yRotO = -90F;
    }

    private static void lookAhead() {
        ClientPlayerEntity p = player();
        p.xRot = 20F;
        p.xRotO = 20F;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (!worldRequested) {
            if (mc.screen instanceof MainMenuScreen) {
                worldRequested = true;
                EcMoons.LOGGER.info(P + "creating test world");
                DynamicRegistries.Impl registries = DynamicRegistries.builtin();
                GameRules rules = new GameRules();
                WorldSettings settings = new WorldSettings("ecmoons-clienttest", GameType.CREATIVE, false, Difficulty.EASY,
                        true, rules, DatapackCodec.DEFAULT);
                mc.createLevel("ecmoons-clienttest", settings, registries, DimensionGeneratorSettings.demoSettings(registries));
                plan();
            }
            return;
        }
        if (mc.player == null && mc.screen instanceof net.minecraft.client.gui.screen.ConfirmScreen) {
            // Vanilla 1.16 asks to confirm "experimental settings" for worlds with data-pack dimensions
            // (the War Moon arena). Singleplayer only; dedicated servers never ask. Accept it.
            for (net.minecraft.client.gui.IGuiEventListener l : mc.screen.children()) {
                if (l instanceof net.minecraft.client.gui.widget.button.Button) {
                    EcMoons.LOGGER.info(P + "confirming screen: " + mc.screen.getTitle().getString() + " -> "
                            + ((net.minecraft.client.gui.widget.button.Button) l).getMessage().getString());
                    ((net.minecraft.client.gui.widget.button.Button) l).onPress();
                    break;
                }
            }
            return;
        }
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (waitUntil != null) {
            if (waitUntil.getAsBoolean() || --waitUntilTimeout <= 0) waitUntil = null;
            else return;
        }
        if (wait-- > 0) return;
        Runnable next = STEPS.poll();
        if (next != null) {
            try {
                next.run();
            } catch (Throwable t) {
                failed++;
                EcMoons.LOGGER.error(P + "FAIL: exception", t);
            }
        }
    }

    private static void plan() {
        then(200, () -> {
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doWeatherCycle false");
            cmd("gamerule doMobSpawning false");
            cmd("weather clear");
            cmd("time set 18000");
            cmd("difficulty peaceful");
            Minecraft.getInstance().options.hideGui = true;
        });
        // ---- moon designs, looking straight up at the midnight (full) moon from above the clouds
        then(40, () -> {
            ClientPlayerEntity p = player();
            px = (int) Math.floor(p.getX());
            pz = (int) Math.floor(p.getZ());
            cmd("tp @a " + px + " 220 " + pz);
            p.abilities.flying = true;
            p.onUpdateAbilities();
        });
        then(60, ClientSelfTest::lookUp);
        then(10, () -> shot("moon_00_ordinary"));
        int i = 1;
        for (MoonType type : MoonType.MOONS) {
            final String name = String.format("moon_%02d_%s", i++, type.id());
            then(1, () -> cmd("ecmoons start " + type.defaultKey));
            waitFor(200, () -> Moons.reported(Minecraft.getInstance().level) == type);
            then(60, () -> {
                check(type.id() + " synced to client", Moons.reported(Minecraft.getInstance().level) == type);
                lookUp();
            });
            then(5, () -> shot(name));
        }
        then(1, () -> cmd("ecmoons start enhancedcelestials-super_blood_moon"));
        then(80, () -> {
            lookUp();
            shot("moon_99_super_blood");
        });

        // ---- Mist fog: fade in, dimension change cleanup, fade out (night vision so the fog is visible in screenshots)
        then(1, () -> {
            int y = Minecraft.getInstance().level.getHeight(Heightmap.Type.MOTION_BLOCKING, px, pz);
            cmd("ecmoons end");
            cmd("tp @a " + px + " " + (y + 10) + " " + pz);
            cmd("effect give @a minecraft:night_vision 600 0 true");
        });
        then(100, ClientSelfTest::lookAhead);
        then(5, () -> {
            shot("mist_0_clear_night");
            cmd("ecmoons start ecmoons-mist_moon");
        });
        then(60, () -> check("mist fading in (factor " + ClientEvents.mistFactor() + ")",
                ClientEvents.mistFactor() > 0.05F && ClientEvents.mistFactor() < 1F));
        waitFor(400, () -> ClientEvents.mistFactor() >= 1F);
        then(5, () -> {
            check("mist fully faded in", ClientEvents.mistFactor() >= 1F);
            lookAhead();
        });
        then(5, () -> shot("mist_1_fog_night"));
        then(5, () -> cmd("execute in minecraft:the_nether run tp @a 0 100 0"));
        then(200, () -> check("no mist in the Nether (factor " + ClientEvents.mistFactor() + ")", ClientEvents.mistFactor() == 0F));
        then(1, () -> cmd("execute in minecraft:overworld run tp @a " + px + " 120 " + pz));
        waitFor(600, () -> ClientEvents.mistFactor() >= 1F);
        then(5, () -> check("mist restored after returning to the overworld", ClientEvents.mistFactor() >= 1F));
        then(1, () -> cmd("ecmoons end"));
        waitFor(400, () -> ClientEvents.mistFactor() <= 0F);
        then(5, () -> check("mist faded out after the moon ended", ClientEvents.mistFactor() <= 0F));

        // ---- Frost: exposure slowdown, overlay, cleanup
        then(1, () -> {
            int y = Minecraft.getInstance().level.getHeight(Heightmap.Type.MOTION_BLOCKING, px, pz);
            cmd("tp @a " + px + " " + y + " " + pz);
            cmd("gamemode survival @a");
            cmd("effect clear @a");
            cmd("difficulty easy");
            cmd("gamerule naturalRegeneration false");
            cmd("ecmoons start ecmoons-frost_moon");
            Minecraft.getInstance().options.hideGui = false;
        });
        waitFor(900, () -> ClientState.frostState() == FrostStatePacket.CHILLED);
        then(80, () -> {
            boolean modifier = player().getAttribute(Attributes.MOVEMENT_SPEED).getModifier(FrostFeatures.SLOW_MODIFIER) != null;
            check("frost: chilled state received", ClientState.frostState() == FrostStatePacket.CHILLED);
            check("frost: movement modifier synced to client", modifier);
            lookAhead();
        });
        then(5, () -> {
            shot("frost_overlay_chilled");
            cmd("ecmoons end");
        });
        then(60, () -> {
            check("frost: modifier removed when the moon ended",
                    player().getAttribute(Attributes.MOVEMENT_SPEED).getModifier(FrostFeatures.SLOW_MODIFIER) == null);
            check("frost: client state cleared", ClientState.frostState() == FrostStatePacket.NONE);
        });

        // ---- Arcane: the real enchanting screen renders the tooltip for the third slot (survival mode, 40 levels)
        then(5, () -> {
            check("arcane tooltip: 3 levels without the lock", enchantTooltip(false) == 3);
            check("arcane tooltip: 2 levels with the server's Arcane lock", enchantTooltip(true) == 2);
        });

        // ---- Server-side rules that need a real player (integrated server thread)
        then(1, () -> cmd("ecmoons end"));
        then(40, () -> onServer(RealPlayerChecks::bloodBaseline));
        then(20, () -> cmd("ecmoons start enhancedcelestials-blood_moon"));
        waitFor(200, () -> Moons.is(Minecraft.getInstance().level, MoonType.BLOOD));
        then(20, () -> onServer(RealPlayerChecks::blood));
        then(20, () -> cmd("ecmoons start ecmoons-prospector_moon"));
        waitFor(200, () -> Moons.is(Minecraft.getInstance().level, MoonType.PROSPECTOR));
        then(20, () -> onServer(RealPlayerChecks::prospector));
        then(20, () -> cmd("ecmoons start ecmoons-wisdom_moon"));
        waitFor(200, () -> Moons.is(Minecraft.getInstance().level, MoonType.WISDOM));
        then(20, () -> onServer(RealPlayerChecks::wisdom));
        then(20, () -> cmd("ecmoons end"));

        // ---- Drift: jump height and fall damage, vanilla first
        then(1, () -> {
            platformY = 150;
            cmd("fill " + (px - 3) + " " + platformY + " " + (pz - 3) + " " + (px + 3) + " " + platformY + " " + (pz + 3) + " minecraft:stone");
            cmd("tp @a " + px + ".5 " + (platformY + 1) + " " + pz + ".5");
            cmd("effect clear @a");
        });
        waitFor(100, () -> player().isOnGround());
        then(10, () -> startJump());
        then(1, () -> {
            vanillaJump = jumpMaxY - jumpStartY;
            EcMoons.LOGGER.info(P + "vanilla jump height " + vanillaJump);
            cmd("ecmoons start ecmoons-drift_moon");
        });
        waitFor(200, () -> Moons.is(Minecraft.getInstance().level, MoonType.DRIFT));
        waitFor(100, () -> player().isOnGround());
        then(10, () -> startJump());
        then(1, () -> {
            driftJump = jumpMaxY - jumpStartY;
            check(String.format("drift jump higher than vanilla (%.2f vs %.2f) but below 2 blocks", driftJump, vanillaJump),
                    driftJump > vanillaJump + 0.15 && driftJump < 1.95);
            cmd("ecmoons end");
        });
        // vanilla fall from 12 blocks
        then(40, () -> {
            cmd("effect give @a minecraft:instant_health 1 10");
        });
        then(20, () -> {
            healthBefore = player().getHealth();
            cmd("tp @a " + px + ".5 " + (platformY + 13) + " " + pz + ".5");
        });
        then(10, () -> {});
        waitFor(200, () -> player().isOnGround());
        then(20, () -> {
            vanillaFallDamage = healthBefore - player().getHealth();
            EcMoons.LOGGER.info(P + "vanilla 12-block fall damage " + vanillaFallDamage);
            cmd("effect give @a minecraft:instant_health 1 10");
            cmd("ecmoons start ecmoons-drift_moon");
        });
        waitFor(200, () -> Moons.is(Minecraft.getInstance().level, MoonType.DRIFT));
        then(20, () -> {
            healthBefore = player().getHealth();
            cmd("tp @a " + px + ".5 " + (platformY + 13) + " " + pz + ".5");
        });
        then(10, () -> {});
        waitFor(300, () -> player().isOnGround());
        then(20, () -> {
            driftFallDamage = healthBefore - player().getHealth();
            // The two falls cover slightly different measured distances (teleport timing); the server log line
            // "Drift fall: ... multiplier 0.5" shows the exact multiplier. Here: clearly less than vanilla.
            check(String.format("drift fall damage reduced (%.1f vs %.1f vanilla)", driftFallDamage, vanillaFallDamage),
                    vanillaFallDamage > 0 && driftFallDamage > 0 && driftFallDamage <= vanillaFallDamage * 0.75);
            cmd("effect give @a minecraft:instant_health 1 10");
        });
        // landing grace: moon ends while airborne
        then(20, () -> {
            healthBefore = player().getHealth();
            cmd("tp @a " + px + ".5 " + (platformY + 30) + " " + pz + ".5");
        });
        then(15, () -> cmd("ecmoons end"));
        waitFor(400, () -> player().isOnGround());
        then(20, () -> {
            float dmg = healthBefore - player().getHealth();
            check(String.format("airborne when Drift ended: landing still reduced (%.1f damage from 29 blocks, vanilla would be 26)", dmg),
                    dmg > 0 && dmg <= 14);
        });

        newMoons();

        then(20, () -> {
            passed += RealPlayerChecks.PASSED.get();
            failed += RealPlayerChecks.FAILED.get();
            EcMoons.LOGGER.info(P + "CLIENTTEST COMPLETE passed=" + passed + " failed=" + failed);
            Minecraft.getInstance().stop();
        });
    }

    private static boolean sawClosed;

    /** Revealing eye and markers, Thread HUD, Gluttony hearts and a solo War Moon match with a real player. */
    private static void newMoons() {
        // ---- Revealing: idle/preview blink frames (looking up at the moon)
        then(20, () -> {
            cmd("effect clear @a");
            cmd("gamemode creative @a");
            cmd("tp @a " + px + " 220 " + pz);
            Minecraft.getInstance().options.hideGui = true;
            cmd("ecmoons start ecmoons-revealing_moon");
        });
        waitFor(200, () -> Moons.is(Minecraft.getInstance().level, MoonType.REVEALING));
        then(40, () -> {
            player().abilities.flying = true;
            player().onUpdateAbilities();
            lookUp();
        });
        then(20, () -> {
            lookUp();
            shot("reveal_0_open");
            cmd("execute as @p run ecmoons reveal blink");
        });
        waitFor(40, () -> RevealClient.frame(0) == 1 || RevealClient.frame(0) == 2);
        then(0, () -> {
            check("revealing: blink passes a half-closed frame (" + RevealClient.frame(0) + ")", RevealClient.frame(0) == 1 || RevealClient.frame(0) == 2);
            lookUp();
            shot("reveal_1_half");
        });
        waitFor(40, () -> RevealClient.frame(0) == 3);
        then(0, () -> {
            check("revealing: blink reaches the closed frame", RevealClient.frame(0) == 3);
            lookUp();
            shot("reveal_2_closed");
        });
        waitFor(60, () -> RevealClient.frame(0) == 0);
        then(1, () -> check("revealing: eye opens again after the blink", RevealClient.frame(0) == 0));
        // server-timed scan: warning -> countdown -> eye shut at the scan -> opens
        then(5, () -> {
            sawClosed = false;
            cmd("ecmoons reveal warn");
        });
        waitFor(200, () -> {
            if (RevealClient.frame(0) == 3 && RevealClient.scanning()) sawClosed = true;
            return sawClosed;
        });
        then(1, () -> check("revealing: the eye closes for the scan after the countdown", sawClosed));
        waitFor(80, () -> RevealClient.frame(0) == 0);
        then(1, () -> check("revealing: the eye reopens after the scan", RevealClient.frame(0) == 0));
        // markers: scan including yourself, then step away and look at your own recorded position
        // (away from the Drift test platform at px,pz; spreadplayers puts the player on the real ground)
        then(5, () -> {
            cmd("gamemode survival @a");
            cmd("spreadplayers " + (px + 40) + " " + pz + " 0 1 false @a");
            Minecraft.getInstance().options.hideGui = false;
        });
        waitFor(100, () -> player().isOnGround());
        then(10, () -> cmd("execute as @p run ecmoons reveal scan self"));
        then(10, () -> {
            check("revealing: scan marker received (" + RevealClient.markerCount() + ")", RevealClient.markerCount() == 1);
            cmd("spreadplayers " + (px + 26) + " " + pz + " 0 1 false @a");
        });
        waitFor(100, () -> player().isOnGround());
        then(15, () -> {
            ClientPlayerEntity p = player();
            p.yRot = -90F; // face east, towards the marker
            p.yRotO = -90F;
            p.xRot = 5F;
            p.xRotO = 5F;
            check("revealing: marker drawn in the world (" + RevealClient.lastDrawn + ")", RevealClient.lastDrawn > 0);
            shot("reveal_3_marker");
        });
        waitFor(200, () -> RevealClient.markerCount() == 0);
        then(1, () -> check("revealing: markers disappear after markerSeconds", RevealClient.markerCount() == 0));
        then(1, () -> cmd("ecmoons end"));
        waitFor(200, () -> Moons.reported(Minecraft.getInstance().level) == MoonType.NONE);

        // ---- Thread HUD (one player cannot bond; this renders the indicator with a local value)
        then(5, () -> ThreadClient.update(0.10F, new int[0]));
        then(5, () -> shot("thread_hud"));
        then(1, () -> ThreadClient.update(0F, new int[0]));

        // ---- Gluttony: real eating event, hearts synced to the client, removed when the moon ends
        then(1, () -> {
            cmd("gamemode survival @a");
            cmd("ecmoons start ecmoons-gluttony_moon");
        });
        waitFor(200, () -> Moons.is(Minecraft.getInstance().level, MoonType.GLUTTONY));
        then(20, () -> onServer(WarPlayerChecks::gluttonyEat));
        then(30, () -> {
            check("gluttony: client sees 24 max health (" + player().getMaxHealth() + ")", player().getMaxHealth() == 24F);
            shot("gluttony_hearts");
            cmd("ecmoons end");
        });
        then(60, () -> check("gluttony: bonus hearts gone when the moon ended (" + player().getMaxHealth() + ")", player().getMaxHealth() == 20F));

        // ---- War Moon: solo test match (save, strip, kit, prep, fight, knockout, restore)
        waitFor(200, () -> player().isOnGround());
        then(10, () -> onServer(WarPlayerChecks::warPrepare));
        then(5, () -> cmd("ecmoons war start solo"));
        waitFor(200, () -> Minecraft.getInstance().level.dimension().equals(net.ecmoons.feature.war.WarArena.DIMENSION));
        then(40, () -> {
            onServer(WarPlayerChecks::warEnrolled);
            lookAhead();
            shot("war_0_cell");
        });
        waitFor(400, () -> net.ecmoons.feature.war.WarFeatures.phase(Minecraft.getInstance().getSingleplayerServer())
                == net.ecmoons.feature.war.WarState.Phase.FIGHT);
        then(20, () -> {
            onServer(WarPlayerChecks::warFight);
            ClientPlayerEntity p = player();
            p.xRot = 10F;
            p.xRotO = 10F;
            shot("war_1_fight");
        });
        then(10, () -> onServer(WarPlayerChecks::warKnockout));
        then(30, () -> {
            ClientPlayerEntity p = player();
            p.xRot = 70F;
            p.xRotO = 70F;
            shot("war_2_gallery");
        });
        waitFor(600, () -> Minecraft.getInstance().level.dimension().equals(net.minecraft.world.World.OVERWORLD));
        then(40, () -> onServer(WarPlayerChecks::warRestored));
        then(5, () -> cmd("ecmoons war champion @p"));
        then(40, () -> onServer(WarPlayerChecks::champion));
        then(5, () -> cmd("gamemode creative @a"));
    }

    private static int enchantTooltip(boolean arcane) {
        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity p = player();
        EnchantmentContainer menu = new EnchantmentContainer(0, p.inventory);
        menu.costs[2] = 30;
        menu.enchantClue[2] = Registry.ENCHANTMENT.getId(Enchantments.SHARPNESS);
        menu.levelClue[2] = 3;
        menu.setData(10, arcane ? 1 : 0); // the add-on's data slot follows vanilla's ten
        int oldLevel = p.experienceLevel;
        p.experienceLevel = 40;
        try {
            EnchantmentScreen screen = new EnchantmentScreen(menu, p.inventory, new StringTextComponent("test"));
            screen.init(mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            int left = (screen.width - 176) / 2;
            int top = (screen.height - 166) / 2;
            ClientState.lastTooltipLevels = -1;
            screen.render(new MatrixStack(), left + 70, top + 14 + 19 * 2 + 5, 0F);
            EcMoons.LOGGER.info(P + "tooltip levels (arcane=" + arcane + "): " + ClientState.lastTooltipLevels);
            return ClientState.lastTooltipLevels;
        } finally {
            p.experienceLevel = oldLevel;
        }
    }

    private static void startJump() {
        ClientPlayerEntity p = player();
        jumpStartY = p.getY();
        jumpMaxY = p.getY();
        p.jumpFromGround();
        // track the apex over the next ticks
        for (int t = 0; t < 25; t++) {
            STEPS.addFirst(() -> jumpMaxY = Math.max(jumpMaxY, player().getY()));
        }
    }
}
