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

    /** A check evaluated on the server thread (counted with the real-player server checks). */
    private static void checkServer(String what, boolean ok) {
        if (ok) {
            RealPlayerChecks.PASSED.incrementAndGet();
            EcMoons.LOGGER.info(P + "PASS (server): " + what);
        } else {
            RealPlayerChecks.FAILED.incrementAndGet();
            EcMoons.LOGGER.error(P + "FAIL (server): " + what);
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
        abilities();
        fieldGuide();
        atmosphere();

        then(20, () -> {
            passed += RealPlayerChecks.PASSED.get();
            failed += RealPlayerChecks.FAILED.get();
            EcMoons.LOGGER.info(P + "CLIENTTEST COMPLETE passed=" + passed + " failed=" + failed);
            Minecraft.getInstance().stop();
        });
    }

    private static boolean sawClosed;
    private static int ripplesBefore, edgesAfter, colourAfter;
    private static double bindX, bindZ;
    private static net.minecraft.util.math.vector.Vector3d vanillaSky;
    private static long gradeBefore;
    private static int maxSeen, ax, ay, az, standId;
    private static boolean sawPartial, sawConstellation;

    private static net.minecraft.util.math.vector.Vector3d sky() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level.getSkyColor(mc.gameRenderer.getMainCamera().getBlockPosition(), 1F);
    }

    private static void lookHorizon() {
        ClientPlayerEntity p = player();
        p.xRot = -12F;
        p.xRotO = -12F;
        p.yRot = -90F;
        p.yRotO = -90F;
    }

    /** Atmosphere: every profile through the admin preview, transitions, quality caps, indoors, real events. */
    private static void atmosphere() {
        then(20, () -> {
            cmd("ecmoons end");
            cmd("gamemode creative @a");
            cmd("effect clear @a");
            cmd("time set 18000");
            cmd("weather clear");
            ax = px + 70;
            az = pz;
            int y = Minecraft.getInstance().level.getHeight(Heightmap.Type.MOTION_BLOCKING, ax, az);
            ay = y + 25;
            player().abilities.flying = true;
            player().onUpdateAbilities();
            cmd("tp @a " + ax + ".5 " + ay + " " + az + ".5 -90 -12");
            Minecraft.getInstance().options.hideGui = true;
        });
        then(60, () -> {
            player().abilities.flying = true;
            player().onUpdateAbilities();
            cmd("tp @a " + ax + ".5 " + ay + " " + az + ".5 -90 -12");
            lookHorizon();
        });
        waitFor(300, () -> !net.ecmoons.client.atmo.Atmosphere.anyActive());
        then(10, () -> {
            vanillaSky = sky();
            check("atmosphere: nothing active on an ordinary night (" + net.ecmoons.client.atmo.Atmosphere.describe() + ")",
                    !net.ecmoons.client.atmo.Atmosphere.anyActive());
            lookHorizon();
            shot("atmo_00_ordinary_night");
            gradeBefore = net.ecmoons.client.atmo.Atmosphere.gradeCalls;
        });
        then(20, () -> check("atmosphere: light-map grading hook is applied (" + (net.ecmoons.client.atmo.Atmosphere.gradeCalls - gradeBefore) + " updates)",
                net.ecmoons.client.atmo.Atmosphere.gradeCalls > gradeBefore));
        int i = 1;
        for (MoonType type : MoonType.MOONS) {
            final String name = String.format("atmo_%02d_%s", i++, type.id());
            then(1, () -> {
                sawPartial = false;
                maxSeen = 0;
                cmd("execute as @p run ecmoons atmosphere preview " + type.id());
            });
            if (type == MoonType.BLOOD) {
                then(40, () -> {
                    float w = net.ecmoons.client.atmo.Atmosphere.weight(type);
                    check("atmosphere: 2 s into an 8 s transition the blend is partial (" + w + ")", w > 0.1F && w < 0.5F);
                });
            }
            waitFor(260, () -> {
                maxSeen = Math.max(maxSeen, net.ecmoons.client.atmo.AtmoParticles.count());
                if (type == MoonType.WISDOM && net.ecmoons.client.atmo.AtmoSky.lastConstellations > 0) sawConstellation = true;
                return net.ecmoons.client.atmo.Atmosphere.weight(type) >= 1F;
            });
            then(60, () -> {
                net.minecraft.util.math.vector.Vector3d sk = sky();
                float[] target = net.ecmoons.client.atmo.AtmoProfile.of(type).sky;
                double before = dist(vanillaSky, target), after = dist(sk, target);
                check("atmosphere " + type.id() + ": fully blended in", net.ecmoons.client.atmo.Atmosphere.weight(type) >= 1F);
                check(String.format("atmosphere %s: sky colour moves towards the profile (%.3f -> %.3f)", type.id(), before, after), after < before);
                check("atmosphere " + type.id() + ": light grading active (" + net.ecmoons.client.atmo.Atmosphere.gradeAmount() + ")",
                        net.ecmoons.client.atmo.Atmosphere.gradeAmount() > 0F);
                check("atmosphere " + type.id() + ": particles within the MEDIUM cap (" + net.ecmoons.client.atmo.AtmoParticles.count() + "/"
                        + net.ecmoons.client.atmo.AtmoParticles.cap() + ")", net.ecmoons.client.atmo.AtmoParticles.count() <= net.ecmoons.client.atmo.AtmoParticles.cap());
                if (type == MoonType.FROST || type == MoonType.WAR || type == MoonType.DRIFT || type == MoonType.ARCANE) {
                    check("atmosphere " + type.id() + ": ambient particles spawn outdoors (" + net.ecmoons.client.atmo.AtmoParticles.count() + ")",
                            net.ecmoons.client.atmo.AtmoParticles.count() > 0);
                }
                if (type == MoonType.ARCANE || type == MoonType.DRIFT || type == MoonType.BLOOD || type == MoonType.WAR || type == MoonType.THREAD
                        || type == MoonType.BLUE || type == MoonType.WISDOM) {
                    check("atmosphere " + type.id() + ": sky layer drawn (" + net.ecmoons.client.atmo.AtmoSky.lastLayers + ")", net.ecmoons.client.atmo.AtmoSky.lastLayers > 0);
                }
                lookHorizon();
                player().abilities.flying = true;
                EcMoons.LOGGER.info(P + type.id() + " " + net.ecmoons.client.atmo.Atmosphere.describe());
            });
            then(3, () -> shot(name));
            if (type == MoonType.ARCANE || type == MoonType.WISDOM || type == MoonType.THREAD || type == MoonType.DRIFT
                    || type == MoonType.WAR || type == MoonType.FROST || type == MoonType.REVEALING) {
                then(1, () -> {
                    ClientPlayerEntity p = player();
                    p.xRot = -55F;
                    p.xRotO = -55F;
                });
                then(3, () -> shot(name + "_sky"));
            }
        }
        then(1, () -> check("atmosphere wisdom: constellation lines appeared", sawConstellation));
        // transition out: preview off -> everything fades back to the untouched night
        then(1, () -> cmd("execute as @p run ecmoons atmosphere preview off"));
        then(40, () -> check("atmosphere: fading out after the event ends (" + net.ecmoons.client.atmo.Atmosphere.describe() + ")",
                net.ecmoons.client.atmo.Atmosphere.anyActive()));
        waitFor(260, () -> !net.ecmoons.client.atmo.Atmosphere.anyActive());
        then(5, () -> {
            net.minecraft.util.math.vector.Vector3d sk = sky();
            check("atmosphere: nothing left after the fade-out (sky " + sk + " vs " + vanillaSky + ", grade " + net.ecmoons.client.atmo.Atmosphere.gradeAmount() + ")",
                    !net.ecmoons.client.atmo.Atmosphere.anyActive() && sk.distanceTo(vanillaSky) < 0.02
                            && net.ecmoons.client.atmo.Atmosphere.gradeAmount() == 0F);
        });
        // quality settings: OFF shows nothing; LOW and HIGH respect their caps
        then(1, () -> {
            net.ecmoons.config.ClientConfig.ATMOSPHERE_QUALITY.set(net.ecmoons.config.ClientConfig.Quality.OFF);
            cmd("execute as @p run ecmoons atmosphere preview war");
        });
        then(100, () -> check("atmosphere quality OFF: no atmosphere and no particles (" + net.ecmoons.client.atmo.Atmosphere.describe() + ")",
                !net.ecmoons.client.atmo.Atmosphere.anyActive() && net.ecmoons.client.atmo.AtmoParticles.count() == 0));
        then(1, () -> {
            net.ecmoons.config.ClientConfig.ATMOSPHERE_QUALITY.set(net.ecmoons.config.ClientConfig.Quality.LOW);
            maxSeen = 0;
        });
        waitFor(400, () -> {
            maxSeen = Math.max(maxSeen, net.ecmoons.client.atmo.AtmoParticles.count());
            return false;
        });
        then(1, () -> check("atmosphere quality LOW: at most 8 particles (max seen " + maxSeen + ")", maxSeen > 0 && maxSeen <= 8));
        then(1, () -> {
            net.ecmoons.config.ClientConfig.ATMOSPHERE_QUALITY.set(net.ecmoons.config.ClientConfig.Quality.HIGH);
            maxSeen = 0;
        });
        waitFor(500, () -> {
            maxSeen = Math.max(maxSeen, net.ecmoons.client.atmo.AtmoParticles.count());
            return false;
        });
        then(1, () -> {
            check("atmosphere quality HIGH: more particles but at most 35 (max seen " + maxSeen + ")", maxSeen > 8 && maxSeen <= 35);
            lookHorizon();
            shot("atmo_war_high");
            net.ecmoons.config.ClientConfig.ATMOSPHERE_QUALITY.set(net.ecmoons.config.ClientConfig.Quality.MEDIUM);
        });
        // War pre-battle build-up through the real tournament warning
        then(1, () -> cmd("ecmoons war start"));
        then(200, () -> check("atmosphere war: the battle warning builds turbulence (" + net.ecmoons.client.atmo.AtmoWar.turbulence() + ")",
                net.ecmoons.client.atmo.AtmoWar.warning() && net.ecmoons.client.atmo.AtmoWar.turbulence() > 0.05F));
        then(1, () -> cmd("ecmoons war cancel"));
        then(20, () -> check("atmosphere war: cancelling ends the build-up", !net.ecmoons.client.atmo.AtmoWar.warning()));
        then(1, () -> cmd("execute as @p run ecmoons atmosphere demo war"));
        waitFor(260, () -> net.ecmoons.client.atmo.AtmoWar.summonFlash() > 0F);
        then(2, () -> {
            check("atmosphere war: one brief summon pulse", net.ecmoons.client.atmo.AtmoWar.summonFlash() > 0F && net.ecmoons.client.atmo.Atmosphere.pulseAmount() > 0F);
            lookHorizon();
            shot("atmo_war_summon_pulse");
        });
        // indoors: a sealed stone room — no sky particles inside
        then(60, () -> {
            cmd("execute as @p run ecmoons atmosphere preview frost");
            int bx = ax, by = ay, bz = az;
            cmd("fill " + (bx - 3) + " " + (by - 2) + " " + (bz - 3) + " " + (bx + 3) + " " + (by + 4) + " " + (bz + 3) + " minecraft:stone hollow");
            cmd("tp @a " + bx + ".5 " + (by - 1) + " " + bz + ".5");
        });
        then(200, () -> {
            int inside = net.ecmoons.client.atmo.AtmoParticles.countInside(ax - 2, ay - 1, az - 2, ax + 3, ay + 4, az + 3);
            check("atmosphere indoors: no frost crystals or breath inside a closed room (inside " + inside + ", exposure "
                    + net.ecmoons.client.atmo.Atmosphere.exposure() + ")", inside == 0 && net.ecmoons.client.atmo.Atmosphere.exposure() < 0.3F);
        });
        then(1, () -> cmd("fill " + (ax - 3) + " " + (ay - 2) + " " + (az - 3) + " " + (ax + 3) + " " + (ay + 4) + " " + (az + 3) + " minecraft:air"));
        // Prospector glints: an exposed ore wall glints; ores hidden behind it never do
        then(5, () -> {
            cmd("execute as @p run ecmoons atmosphere preview prospector");
            int gx = ax + 4;
            cmd("fill " + gx + " " + (ay - 1) + " " + (az - 2) + " " + (gx + 2) + " " + (ay + 3) + " " + (az + 2) + " minecraft:stone");
            cmd("fill " + gx + " " + ay + " " + (az - 1) + " " + gx + " " + (ay + 2) + " " + (az + 1) + " minecraft:iron_ore");
            cmd("fill " + (gx + 1) + " " + ay + " " + (az - 1) + " " + (gx + 1) + " " + (ay + 2) + " " + (az + 1) + " minecraft:gold_ore");
            cmd("tp @a " + ax + ".5 " + ay + " " + az + ".5 -90 0");
        });
        then(400, () -> {
            java.util.List<net.minecraft.util.math.BlockPos> g = net.ecmoons.client.atmo.AtmoParticles.glintBlocks();
            boolean onlyExposed = true;
            for (net.minecraft.util.math.BlockPos bp : g) if (bp.getX() != ax + 4) onlyExposed = false;
            check("atmosphere prospector: exposed ore faces in view glint (" + g.size() + ")", !g.isEmpty());
            check("atmosphere prospector: ore hidden behind other blocks never glints (no x-ray)", onlyExposed);
            Minecraft.getInstance().options.hideGui = true;
            ClientPlayerEntity p = player();
            p.yRot = -90F; p.yRotO = -90F; p.xRot = 10F; p.xRotO = 10F;
        });
        then(3, () -> shot("atmo_prospector_ore"));
        then(1, () -> cmd("fill " + (ax + 4) + " " + (ay - 1) + " " + (az - 2) + " " + (ax + 6) + " " + (ay + 3) + " " + (az + 2) + " minecraft:air"));
        // Thread line between the player and a stand-in (one test player cannot bond on the server)
        then(5, () -> {
            cmd("execute as @p run ecmoons atmosphere preview thread");
            cmd("summon minecraft:armor_stand " + (ax + 5) + ".5 " + ay + " " + az + ".5 {NoGravity:1b,Tags:[\"ecmtest\"]}");
        });
        then(20, () -> {
            for (net.minecraft.entity.Entity e : Minecraft.getInstance().level.entitiesForRendering()) {
                if (e instanceof net.minecraft.entity.item.ArmorStandEntity) standId = e.getId();
            }
            ThreadClient.update(0.05F, new int[]{player().getId(), standId});
            lookHorizon();
        });
        then(40, () -> {
            check("atmosphere thread: a thin thread is drawn between bonded teammates (" + ThreadClient.lastDrawnLines + ")", ThreadClient.lastDrawnLines == 1);
            shot("atmo_thread_line");
            ThreadClient.update(0F, new int[0]);
        });
        then(25, () -> check("atmosphere thread: the thread fades out gradually (still visible after 1.2 s)", ThreadClient.lastDrawnLines == 1));
        then(60, () -> {
            check("atmosphere thread: gone after the fade", ThreadClient.lastDrawnLines == 0);
            cmd("kill @e[tag=ecmtest]");
        });
        // real lunar event (no preview), and dimension change
        then(1, () -> {
            cmd("execute as @p run ecmoons atmosphere preview off");
            cmd("ecmoons start enhancedcelestials-blood_moon");
        });
        waitFor(400, () -> net.ecmoons.client.atmo.Atmosphere.weight(MoonType.BLOOD) >= 1F);
        then(1, () -> {
            check("atmosphere: follows the real Enhanced Celestials event (" + net.ecmoons.client.atmo.Atmosphere.describe() + ")",
                    net.ecmoons.client.atmo.Atmosphere.target() == MoonType.BLOOD);
            cmd("execute in minecraft:the_nether run tp @a 0 100 0");
        });
        then(100, () -> check("atmosphere: no moon atmosphere in the Nether (" + net.ecmoons.client.atmo.Atmosphere.describe() + ")",
                net.ecmoons.client.atmo.Atmosphere.target() == MoonType.NONE));
        then(1, () -> cmd("execute in minecraft:overworld run tp @a " + ax + " " + ay + " " + az));
        waitFor(400, () -> net.ecmoons.client.atmo.Atmosphere.weight(MoonType.BLOOD) >= 1F);
        then(1, () -> {
            check("atmosphere: fades back in after returning to the overworld", net.ecmoons.client.atmo.Atmosphere.weight(MoonType.BLOOD) >= 1F);
            cmd("ecmoons end");
        });
        waitFor(300, () -> !net.ecmoons.client.atmo.Atmosphere.anyActive());
        then(1, () -> {
            check("atmosphere: event end restores the normal night", !net.ecmoons.client.atmo.Atmosphere.anyActive());
            Minecraft.getInstance().options.hideGui = false;
        });
    }

    private static double dist(net.minecraft.util.math.vector.Vector3d v, float[] c) {
        float k = net.ecmoons.client.atmo.Atmosphere.SKY_SCALE;
        double dx = v.x - c[0] * k, dy = v.y - c[1] * k, dz = v.z - c[2] * k;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

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
        // server-timed cycle with a real survival player standing on a glass platform under the moon (midnight):
        // opening (narrow, then open wide) -> active scan (fully open, Binding Gaze) -> completion (ONE ripple, the
        // reveal, closing, binds released) -> this player's own reveal (True Exposure, amber edges, colour loss)
        then(5, () -> {
            cmd("fill " + (px - 2) + " 218 " + (pz - 2) + " " + (px + 2) + " 218 " + (pz + 2) + " minecraft:glass");
            cmd("tp @a " + px + ".5 219 " + pz + ".5");
            cmd("gamemode survival @a");
            cmd("effect give @a minecraft:invisibility 120 0 true");
            ripplesBefore = RevealClient.ripplesStarted;
        });
        then(20, () -> {
            lookUp();
            cmd("ecmoons reveal warn");
        });
        waitFor(120, () -> RevealClient.phase() == RevealClient.WARN && RevealClient.frame(0) == 1);
        then(0, () -> {
            check("revealing: while the eye opens the eyelids first narrow (frame " + RevealClient.frame(0) + ") and the glow builds ("
                    + RevealClient.haloAmount(0) + ")", RevealClient.phase() == RevealClient.WARN && RevealClient.frame(0) == 1 && RevealClient.haloAmount(0) > 0.2f);
            check("revealing: no ripple when the eye starts opening", RevealClient.ringProgress(0) < 0);
            lookUp();
            shot("reveal_4_warning");
        });
        waitFor(120, () -> RevealClient.phase() == RevealClient.OPEN);
        then(2, () -> {
            check("revealing: active scanning - the eye is fully open with its strong glow (frame " + RevealClient.frame(0) + ", glow "
                    + RevealClient.haloAmount(0) + ") and still no ripple", RevealClient.frame(0) == 0 && RevealClient.haloAmount(0) >= 0.99f
                    && RevealClient.ringProgress(0) < 0);
            lookUp();
            shot("reveal_5_active_open");
        });
        waitFor(60, RevealClient::bound);
        then(0, () -> {
            check("binding gaze: two seconds of staring at the open eye binds (client)", RevealClient.bound());
            onServer(s -> {
                net.minecraft.entity.player.ServerPlayerEntity sp = s.getPlayerList().getPlayers().get(0);
                checkServer("binding gaze: the server bound the player", net.ecmoons.feature.reveal.BindingGaze.isBound(sp));
            });
            bindX = player().getX();
            bindZ = player().getZ();
            Minecraft.getInstance().options.keyUp.setDown(true);
        });
        then(6, () -> {
            check("binding gaze: holding the forward key does not walk (moved " + String.format("%.3f", Math.abs(player().getX() - bindX)
                    + Math.abs(player().getZ() - bindZ)) + ")", Math.abs(player().getX() - bindX) + Math.abs(player().getZ() - bindZ) < 0.01);
            Minecraft.getInstance().options.keyUp.setDown(false);
            player().xRot = 0F; // look away
            player().xRotO = 0F;
        });
        then(2, () -> {
            check("binding gaze: looking away releases at once", !RevealClient.bound());
            onServer(s -> checkServer("binding gaze: the server released it too",
                    !net.ecmoons.feature.reveal.BindingGaze.isBound(s.getPlayerList().getPlayers().get(0))));
            lookUp();
        });
        waitFor(80, () -> RevealClient.ringProgress(0) >= 0);
        then(0, () -> {
            check("revealing: ONE golden ripple starts at scan completion (ripples " + (RevealClient.ripplesStarted - ripplesBefore)
                    + "), the eye begins to close and the light pulses", RevealClient.ripplesStarted - ripplesBefore == 1
                    && RevealClient.phase() == RevealClient.RECOVER && net.ecmoons.client.atmo.Atmosphere.pulseAmount() > 0f);
            check("revealing: completion releases binds and starts YOUR reveal", !RevealClient.bound() && RevealClient.revealed());
            lookUp();
        });
        then(10, () -> {
            lookUp();
            shot("reveal_6_ripple_moon");
            check("revealing: the reveal effects eased in (intensity " + RevealClient.revealIntensity(0) + ")", RevealClient.revealIntensity(0) > 0.8f);
        });
        then(14, () -> {
            ClientPlayerEntity p = player();
            p.yRot = 0F; // face south, away from the moon overhead: the same wave is crossing this part of the sky
            p.yRotO = 0F;
            p.xRot = -20F;
            p.xRotO = -20F;
        });
        then(2, () -> {
            shot("reveal_7_ripple_sky");
            check("revealing: the ripple is drawn across the sky (progress " + net.ecmoons.client.atmo.AtmoSky.lastRipple + ")",
                    net.ecmoons.client.atmo.AtmoSky.lastRipple > 0.2f);
            onServer(s -> net.ecmoons.feature.RevealingFeatures.sync(s.getPlayerList().getPlayers().get(0))); // a late packet
        });
        then(4, () -> check("revealing: a late sync joins the same wave instead of starting another (ripples "
                + (RevealClient.ripplesStarted - ripplesBefore) + ")", RevealClient.ripplesStarted - ripplesBefore == 1 && RevealClient.ringProgress(0) > 0.3f));
        then(1, () -> {
            Minecraft.getInstance().options.hideGui = false;
            lookAhead();
        });
        then(3, () -> {
            shot("reveal_8_seen");
            check("revealing: amber edges and colour loss are drawn for the revealed player (edges " + RevealHud.edgesDrawn + ", colour pass "
                    + RevealGrade.applied + ", saturation " + RevealGrade.lastSaturation + ")", RevealHud.edgesDrawn > 0
                    && (RevealGrade.applied > 0 || RevealGrade.usingWash()) && RevealGrade.lastSaturation < 0.6f);
            check("true exposure: your invisibility is suppressed while revealed, the potion still there",
                    !player().isInvisible() && player().hasEffect(net.minecraft.potion.Effects.INVISIBILITY));
            onServer(s -> {
                net.minecraft.entity.player.ServerPlayerEntity sp = s.getPlayerList().getPlayers().get(0);
                checkServer("true exposure: the server agrees (visible, effect kept, revealed)", !sp.isInvisible()
                        && sp.hasEffect(net.minecraft.potion.Effects.INVISIBILITY) && net.ecmoons.feature.reveal.RevealState.isRevealed(sp));
            });
        });
        then(40, () -> check("revealing: the amber/colour strength follows your own timer (" + RevealClient.revealIntensity(0) + " vs "
                + RevealClient.revealRemaining(0) / 160f + " remaining)", Math.abs(RevealClient.revealIntensity(0) - RevealClient.revealRemaining(0) / 160f) < 0.03f
                && RevealClient.revealIntensity(0) > 0.2f && RevealClient.revealIntensity(0) < 0.8f));
        then(0, () -> shot("reveal_9_fading"));
        waitFor(200, () -> !RevealClient.revealed());
        then(3, () -> {
            check("revealing: at zero the amber edges and colour loss are gone", RevealClient.revealIntensity(0) == 0f);
            check("true exposure: invisibility resumes afterwards because the potion still runs", player().isInvisible()
                    && player().hasEffect(net.minecraft.potion.Effects.INVISIBILITY));
            edgesAfter = RevealHud.edgesDrawn;
            colourAfter = RevealGrade.applied;
            onServer(s -> net.ecmoons.feature.RevealingFeatures.sync(s.getPlayerList().getPlayers().get(0))); // after the wave
        });
        then(5, () -> {
            check("revealing: nothing is drawn after the timer (edges " + (RevealHud.edgesDrawn - edgesAfter) + ", colour " + (RevealGrade.applied - colourAfter) + ")",
                    RevealHud.edgesDrawn == edgesAfter && RevealGrade.applied == colourAfter);
            check("revealing: a finished ripple is never replayed by a later sync", RevealClient.ripplesStarted - ripplesBefore == 1 && RevealClient.ringProgress(0) < 0);
            shot("reveal_10_after");
            cmd("effect clear @a");
            cmd("gamemode creative @a");
            Minecraft.getInstance().options.hideGui = true;
        });
        waitFor(120, () -> RevealClient.phase() == RevealClient.NONE || RevealClient.phase() == RevealClient.BLINK);
        then(1, () -> check("revealing: the eye reopens and returns to idle", RevealClient.frame(0) == 0 || RevealClient.phase() == RevealClient.BLINK));
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
            onServer(s -> { // to the ground 15 blocks west of the marker (spreadplayers can refuse a spot)
                net.minecraft.entity.player.ServerPlayerEntity sp = s.getPlayerList().getPlayers().get(0);
                int x = px + 26, z = pz;
                int y = sp.getLevel().getHeight(net.minecraft.world.gen.Heightmap.Type.MOTION_BLOCKING, x, z);
                sp.teleportTo(x + 0.5, y, z + 0.5);
            });
        });
        waitFor(100, () -> player().isOnGround());
        then(15, () -> {
            ClientPlayerEntity p = player();
            p.yRot = -90F; // face east, towards the marker
            p.yRotO = -90F;
            p.xRot = 5F;
            p.xRotO = 5F;
            check("revealing: marker drawn in the world (" + RevealClient.lastDrawn + ")", RevealClient.lastDrawn > 0);
            Minecraft.getInstance().options.hideGui = true; // the chat would cover the marker in the screenshot
        });
        then(2, () -> {
            shot("reveal_3_marker");
            Minecraft.getInstance().options.hideGui = false;
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

    private static double abX;
    private static float controlDamage;
    private static int bonusHitsBefore;

    private static void faceEast() {
        ClientPlayerEntity p = player();
        p.yRot = -90F;
        p.yRotO = -90F;
        p.xRot = 10F;
        p.xRotO = 10F;
    }

    private static void tap() {
        net.ecmoons.client.ability.AbilityClient.testKey(true);
        STEPS.addFirst(() -> net.ecmoons.client.ability.AbilityClient.testKey(false));
    }

    /** Moon Ability key with a real player: Afterimage Frame Step and Shell Moon shell. */
    private static String tpStart() {
        return "tp @a " + (ax + 0.5) + " " + ay + " " + (az + 0.5) + " -90 10";
    }

    private static String tpShell() {
        return "tp @a " + (ax + 10.5) + " " + ay + " " + (az + 0.5) + " -90 10";
    }

    private static void abilities() {
        // coordinates are only known once the world exists (px/pz are measured at run time)
        then(5, () -> {
            Minecraft.getInstance().options.chatVisibility = net.minecraft.entity.player.ChatVisibility.HIDDEN; // clear screenshots
            ax = px + 80;
            az = pz;
            ay = 200;
            cmd("gamemode creative @a");
            player().abilities.flying = true;
            player().onUpdateAbilities();
            cmd(tpStart());
        });
        then(40, () -> {
            cmd("gamerule doMobSpawning false");
            cmd("gamerule naturalRegeneration false");
            cmd("gamerule doMobLoot false");
            cmd("kill @e[type=!player]");
            cmd("effect clear @a");
            cmd("gamemode survival @a");
            cmd("fill " + (ax - 20) + " " + (ay - 1) + " " + (az - 15) + " " + (ax + 24) + " " + (ay - 1) + " " + (az + 15) + " obsidian");
            cmd("fill " + (ax - 20) + " " + ay + " " + (az - 15) + " " + (ax + 24) + " " + (ay + 5) + " " + (az + 15) + " air");
            cmd(tpStart());
            cmd("ecmoons start ecmoons-afterimage_moon");
        });
        waitFor(200, () -> Moons.is(Minecraft.getInstance().level, MoonType.AFTERIMAGE));
        waitFor(100, () -> player().isOnGround());
        // a still target 1.8 blocks past where the three steps (2 + 3 + 4 = 9) should end
        then(20, () -> {
            cmd("summon zombie " + (ax + 11.3) + " " + ay + " " + (az + 0.5) + " {NoAI:1b,Silent:1b,IsBaby:0b,PersistenceRequired:1b,Tags:[\"ectest\"]}");
            Minecraft.getInstance().options.setCameraType(net.minecraft.client.settings.PointOfView.THIRD_PERSON_FRONT);
            faceEast();
            onServer(net.ecmoons.selftest.AbilityPlayerChecks::snapshot);
        });
        then(3, () -> {
            abX = net.ecmoons.selftest.AbilityPlayerChecks.x;
            bonusHitsBefore = net.ecmoons.selftest.AbilityPlayerChecks.bonusHits;
            faceEast();
            tap();
        });
        waitFor(20, () -> net.ecmoons.client.ability.AbilityClient.afterimagePhase() == 2 && net.ecmoons.client.ability.AbilityClient.afterimageStage() == 1);
        then(0, () -> {
            check("afterimage: step 1 done and the input window opened (HUD phase " + net.ecmoons.client.ability.AbilityClient.afterimagePhase() + ")",
                    net.ecmoons.client.ability.AbilityClient.afterimageStage() == 1);
            check("afterimage: an afterimage is shown where the step started (" + net.ecmoons.client.ability.AbilityFx.ghostCount() + ")",
                    net.ecmoons.client.ability.AbilityFx.ghostCount() > 0);
            tap();
        });
        waitFor(20, () -> net.ecmoons.client.ability.AbilityClient.afterimagePhase() == 2 && net.ecmoons.client.ability.AbilityClient.afterimageStage() == 2);
        then(0, () -> {
            check("afterimage: step 2 done inside the window", net.ecmoons.client.ability.AbilityClient.afterimageStage() == 2);
            tap();
            shot("afterimage_0_steps");
        });
        waitFor(20, () -> net.ecmoons.client.ability.AbilityClient.afterimagePhase() == 3);
        then(0, () -> {
            check("afterimage: third step done, final attack window on the HUD", net.ecmoons.client.ability.AbilityClient.afterimagePhase() == 3);
            check("afterimage: afterimages drawn (" + net.ecmoons.client.ability.AbilityFx.lastGhostsDrawn + ")", net.ecmoons.client.ability.AbilityFx.lastGhostsDrawn > 0);
            shot("afterimage_1_final_window");
            ClientPlayerEntity p = player();
            java.util.List<net.minecraft.entity.monster.ZombieEntity> z = Minecraft.getInstance().level.getEntitiesOfClass(
                    net.minecraft.entity.monster.ZombieEntity.class, p.getBoundingBox().inflate(4));
            check("afterimage: target zombie in reach after the dash (" + z.size() + ")", !z.isEmpty());
            if (!z.isEmpty()) {
                Minecraft.getInstance().gameMode.attack(p, z.get(0));
                p.swing(net.minecraft.util.Hand.MAIN_HAND);
            }
        });
        then(3, () -> onServer(net.ecmoons.selftest.AbilityPlayerChecks::snapshot));
        then(2, () -> {
            double s1 = net.ecmoons.selftest.AbilityPlayerChecks.step1, s2 = net.ecmoons.selftest.AbilityPlayerChecks.step2,
                    s3 = net.ecmoons.selftest.AbilityPlayerChecks.step3, moved = net.ecmoons.selftest.AbilityPlayerChecks.x - abX;
            check(String.format("afterimage: step distances %.2f / %.2f / %.2f (expected 2 / 3 / 4)", s1, s2, s3),
                    Math.abs(s1 - 2) < 0.05 && Math.abs(s2 - 3) < 0.05 && Math.abs(s3 - 4) < 0.05);
            check(String.format("afterimage: total movement %.2f blocks east (expected 9)", moved), Math.abs(moved - 9) < 0.3);
            check("afterimage: the first melee hit got the knockback bonus (" + net.ecmoons.selftest.AbilityPlayerChecks.knockback + ", hits "
                    + (net.ecmoons.selftest.AbilityPlayerChecks.bonusHits - bonusHitsBefore) + ")",
                    net.ecmoons.selftest.AbilityPlayerChecks.bonusHits - bonusHitsBefore == 1 && Math.abs(net.ecmoons.selftest.AbilityPlayerChecks.knockback - 0.64F) < 0.02F);
            check("afterimage: bonus consumed by that hit", !net.ecmoons.selftest.AbilityPlayerChecks.bonus);
            check("afterimage: cooldown started (" + net.ecmoons.selftest.AbilityPlayerChecks.cooldown + " ticks)",
                    net.ecmoons.selftest.AbilityPlayerChecks.cooldown > 100);
        });
        // second hit after the attack cooldown: ordinary knockback
        then(20, () -> {
            ClientPlayerEntity p = player();
            java.util.List<net.minecraft.entity.monster.ZombieEntity> z = Minecraft.getInstance().level.getEntitiesOfClass(
                    net.minecraft.entity.monster.ZombieEntity.class, p.getBoundingBox().inflate(5));
            if (!z.isEmpty()) {
                Minecraft.getInstance().gameMode.attack(p, z.get(0));
                p.swing(net.minecraft.util.Hand.MAIN_HAND);
            }
        });
        then(3, () -> onServer(net.ecmoons.selftest.AbilityPlayerChecks::snapshot));
        then(2, () -> check("afterimage: the next hit has normal knockback (" + net.ecmoons.selftest.AbilityPlayerChecks.knockback + ")",
                net.ecmoons.selftest.AbilityPlayerChecks.bonusHits - bonusHitsBefore == 1 && Math.abs(net.ecmoons.selftest.AbilityPlayerChecks.knockback - 0.4F) < 0.02F));
        // cooldown: pressing again does nothing
        then(1, () -> {
            abX = player().getX();
            faceEast();
            tap();
        });
        then(15, () -> check(String.format("afterimage: on cooldown the key does not move you (%.2f)", player().getX() - abX),
                Math.abs(player().getX() - abX) < 0.05));
        // pressing too early (while the first step is still moving) breaks the sequence with a short slowdown
        then(1, () -> {
            cmd("kill @e[tag=ectest]");
            cmd("ecmoons ability reset @p");
            cmd(tpStart());
        });
        waitFor(60, () -> player().isOnGround());
        // a second press one tick later (sent directly; the key itself only reports edges): the 3-tick step is still moving
        then(10, () -> {
            faceEast();
            net.ecmoons.client.ability.AbilityClient.testKey(true);
            STEPS.addFirst(() -> {
                net.ecmoons.client.ability.AbilityClient.testKey(false);
                net.ecmoons.net.Network.CHANNEL.sendToServer(new net.ecmoons.net.AbilityPacket(net.ecmoons.net.AbilityPacket.PRESS, 0, 0));
            });
        });
        then(2, () -> onServer(net.ecmoons.selftest.AbilityPlayerChecks::snapshot));
        then(2, () -> check("afterimage: a too-early press breaks the sequence with a slowdown and starts the cooldown (stage "
                + net.ecmoons.selftest.AbilityPlayerChecks.stage + ", penalised " + net.ecmoons.selftest.AbilityPlayerChecks.penalised + ", cd "
                + net.ecmoons.selftest.AbilityPlayerChecks.cooldown + ")", net.ecmoons.selftest.AbilityPlayerChecks.stage == 0
                && net.ecmoons.selftest.AbilityPlayerChecks.penalised && net.ecmoons.selftest.AbilityPlayerChecks.cooldown > 100));
        then(15, () -> onServer(net.ecmoons.selftest.AbilityPlayerChecks::snapshot));
        then(2, () -> check("afterimage: the slowdown wears off after penaltyTicks", !net.ecmoons.selftest.AbilityPlayerChecks.penalised));
        // a wall stops the step: nothing passes through blocks
        then(1, () -> {
            cmd("ecmoons ability reset @p");
            cmd(tpStart());
            cmd("fill " + (ax + 2) + " " + ay + " " + (az - 2) + " " + (ax + 2) + " " + (ay + 2) + " " + (az + 2) + " stone");
        });
        waitFor(60, () -> player().isOnGround());
        then(10, () -> {
            faceEast();
            tap();
        });
        then(10, () -> onServer(net.ecmoons.selftest.AbilityPlayerChecks::snapshot));
        then(2, () -> {
            double x = net.ecmoons.selftest.AbilityPlayerChecks.x;
            check(String.format("afterimage: a wall stops the step before it (x %.2f, wall face at %d)", x, ax + 2),
                    x <= ax + 2 - 0.3 + 1e-3 && x > ax + 1.2);
            cmd("fill " + (ax + 2) + " " + ay + " " + (az - 2) + " " + (ax + 2) + " " + (ay + 2) + " " + (az + 2) + " air");
            cmd("ecmoons ability reset @p");
            cmd("ecmoons end");
        });
        waitFor(200, () -> Moons.reported(Minecraft.getInstance().level) == MoonType.NONE);

        // ---- Shell Moon: crouch + hold to charge, collapse on release, explosion reduction
        then(5, () -> {
            cmd(tpShell());
            // a wall right behind the player so the explosion's push can't throw them off the platform
            cmd("fill " + (ax + 9) + " " + ay + " " + (az - 3) + " " + (ax + 9) + " " + (ay + 4) + " " + (az + 3) + " obsidian");
            cmd("ecmoons start ecmoons-shell_moon");
            Minecraft.getInstance().options.setCameraType(net.minecraft.client.settings.PointOfView.THIRD_PERSON_FRONT);
        });
        waitFor(200, () -> Moons.is(Minecraft.getInstance().level, MoonType.SHELL));
        waitFor(100, () -> player().isOnGround());
        // control: the same explosion without a shell
        then(10, () -> onServer(net.ecmoons.selftest.AbilityPlayerChecks::prepareExplosion));
        then(3, () -> cmd("summon tnt " + (ax + 13) + " " + ay + " " + (az + 0.5) + " {Fuse:1}"));
        then(15, () -> onServer(net.ecmoons.selftest.AbilityPlayerChecks::snapshot));
        then(2, () -> {
            controlDamage = player().getMaxHealth() - net.ecmoons.selftest.AbilityPlayerChecks.health;
            EcMoons.LOGGER.info(P + "control explosion damage " + controlDamage);
            cmd(tpShell());
        });
        waitFor(60, () -> player().isOnGround());
        // pressing without crouching does nothing
        then(10, () -> {
            faceEast();
            tap();
        });
        then(5, () -> check("shell: without crouching the key raises nothing (" + net.ecmoons.client.ability.AbilityClient.shellState() + ")",
                net.ecmoons.client.ability.AbilityClient.shellState() == 0));
        then(1, () -> Minecraft.getInstance().options.keyShift.setDown(true));
        then(5, () -> net.ecmoons.client.ability.AbilityClient.testKey(true));
        waitFor(10, () -> net.ecmoons.client.ability.AbilityClient.shellState() == 1);
        then(0, () -> {
            check("shell: crouch + hold starts charging (HUD)", net.ecmoons.client.ability.AbilityClient.shellState() == 1);
            shot("shell_0_charging");
        });
        waitFor(80, () -> net.ecmoons.client.ability.AbilityClient.shellState() == 2);
        then(5, () -> {
            check("shell: deployed after the charge", net.ecmoons.client.ability.AbilityClient.shellState() == 2);
            check("shell: shell drawn (" + net.ecmoons.client.ability.AbilityFx.lastShellsDrawn + ")", net.ecmoons.client.ability.AbilityFx.lastShellsDrawn > 0);
            onServer(net.ecmoons.selftest.AbilityPlayerChecks::snapshot);
            shot("shell_1_up");
        });
        then(2, () -> {
            check("shell: server has the shell", net.ecmoons.selftest.AbilityPlayerChecks.shell);
            net.ecmoons.client.ability.AbilityClient.testKey(false);
        });
        then(5, () -> onServer(net.ecmoons.selftest.AbilityPlayerChecks::snapshot));
        then(2, () -> check("shell: releasing the key collapses it and starts the cooldown (cd " + net.ecmoons.selftest.AbilityPlayerChecks.shellCooldown + ")",
                !net.ecmoons.selftest.AbilityPlayerChecks.shell && net.ecmoons.selftest.AbilityPlayerChecks.shellCooldown > 300
                        && net.ecmoons.client.ability.AbilityClient.shellState() == 0));
        then(40, () -> check("shell: the shell fades away on the client (" + net.ecmoons.client.ability.AbilityFx.shellCount() + ")",
                net.ecmoons.client.ability.AbilityFx.shellCount() == 0));
        // with a shell: the same explosion
        then(1, () -> {
            cmd("ecmoons ability reset @p");
            net.ecmoons.client.ability.AbilityClient.testKey(true);
        });
        waitFor(80, () -> net.ecmoons.client.ability.AbilityClient.shellState() == 2);
        then(2, () -> onServer(net.ecmoons.selftest.AbilityPlayerChecks::prepareExplosion));
        then(3, () -> cmd("summon tnt " + (ax + 13) + " " + ay + " " + (az + 0.5) + " {Fuse:1}"));
        then(15, () -> onServer(net.ecmoons.selftest.AbilityPlayerChecks::snapshot));
        then(2, () -> {
            float dmg = player().getMaxHealth() - net.ecmoons.selftest.AbilityPlayerChecks.health;
            check(String.format("shell: explosion damage inside the shell %.1f vs %.1f without (75%% less expected)", dmg, controlDamage),
                    controlDamage > 2 && dmg > 0 && Math.abs(dmg - controlDamage * 0.25F) < Math.max(0.6F, controlDamage * 0.08F));
            net.ecmoons.client.ability.AbilityClient.testKey(false);
            Minecraft.getInstance().options.keyShift.setDown(false);
        });
        wispTests();
        then(5, () -> {
            cmd("ecmoons ability reset @p");
            cmd("ecmoons end");
            cmd("effect clear @a");
            cmd("gamerule doMobSpawning true");
            cmd("gamerule naturalRegeneration true");
            cmd("gamerule doMobLoot true");
            Minecraft.getInstance().options.chatVisibility = net.minecraft.entity.player.ChatVisibility.FULL;
            cmd("gamemode creative @a");
            cmd("fill " + (ax - 20) + " " + (ay - 1) + " " + (az - 15) + " " + (ax + 24) + " " + (ay + 5) + " " + (az + 15) + " air");
            cmd("tp @a " + px + " 150 " + pz); // back near the start so the next tests find loaded terrain
            Minecraft.getInstance().options.setCameraType(net.minecraft.client.settings.PointOfView.FIRST_PERSON);
        });
        waitFor(200, () -> Moons.reported(Minecraft.getInstance().level) == MoonType.NONE);
    }

    private static double wispStartX;
    private static net.minecraft.entity.Entity wispCam;

    private static net.ecmoons.client.ability.WispMenuScreen menu() {
        net.minecraft.client.gui.screen.Screen sc = Minecraft.getInstance().screen;
        return sc instanceof net.ecmoons.client.ability.WispMenuScreen ? (net.ecmoons.client.ability.WispMenuScreen) sc : null;
    }

    /** Wisp Moon with a real client: menu, camera on the wisp, flight, wall, observer placement, viewing, exits. */
    private static void wispTests() {
        then(5, () -> {
            net.ecmoons.client.ability.AbilityClient.testKey(false);
            Minecraft.getInstance().options.keyShift.setDown(false);
            cmd("ecmoons ability reset @p");
            cmd("effect clear @a");
            cmd("fill " + (ax + 9) + " " + ay + " " + (az - 3) + " " + (ax + 9) + " " + (ay + 4) + " " + (az + 3) + " air");
            cmd("fill " + (ax + 6) + " " + ay + " " + (az - 3) + " " + (ax + 6) + " " + (ay + 4) + " " + (az + 3) + " stone");
            cmd(tpStart());
            cmd("ecmoons start ecmoons-wisp_moon");
            Minecraft.getInstance().options.setCameraType(net.minecraft.client.settings.PointOfView.FIRST_PERSON);
        });
        waitFor(200, () -> Moons.is(Minecraft.getInstance().level, MoonType.WISP));
        waitFor(100, () -> player().isOnGround());
        then(10, () -> {
            faceEast();
            tap();
        });
        waitFor(40, () -> menu() != null);
        then(2, () -> {
            check("wisp: the Moon Ability key opens the Wisp menu", menu() != null);
            shot("wisp_0_menu");
            wispStartX = player().getX();
            net.ecmoons.client.ability.WispClient.send(net.ecmoons.feature.ability.WispAbility.PILOT, 0);
        });
        waitFor(40, () -> Minecraft.getInstance().getCameraEntity() instanceof net.ecmoons.feature.ability.wisp.WispEntity);
        then(2, () -> {
            wispCam = Minecraft.getInstance().getCameraEntity();
            check("wisp: piloting switches the camera to the wisp (" + wispCam + ")", wispCam instanceof net.ecmoons.feature.ability.wisp.WispEntity);
            check("wisp: the menu closed and the game mode is unchanged", menu() == null
                    && Minecraft.getInstance().gameMode.getPlayerMode() == net.minecraft.world.GameType.SURVIVAL);
            faceEast();
            net.ecmoons.client.ability.WispClient.testInput = new float[]{1, 0, 0};
        });
        then(12, () -> {
            net.ecmoons.client.ability.WispClient.testInput = new float[]{0, 0, 0};
            double moved = wispCam.getX() - wispStartX;
            check(String.format("wisp: the wisp flies with the movement keys (%.2f blocks east)", moved), moved > 2.5);
            check(String.format("wisp: the body stays where it was (%.3f)", player().getX() - wispStartX), Math.abs(player().getX() - wispStartX) < 0.01);
            net.ecmoons.client.ability.WispClient.testInput = new float[]{1, 0, 0};
        });
        then(20, () -> {
            net.ecmoons.client.ability.WispClient.testInput = new float[]{0, 0, 0};
            check(String.format("wisp: a wall stops the wisp (x %.2f, wall face %d)", wispCam.getX() - ax, 6),
                    wispCam.getX() + wispCam.getBbWidth() / 2 <= ax + 6 + 0.02);
            net.minecraft.client.settings.KeyBinding.click(Minecraft.getInstance().options.keyUse.getKey()); // explicit placement input
        });
        waitFor(40, () -> net.ecmoons.client.ability.WispClient.count == 1);
        then(5, () -> {
            check("wisp: the Use key places a soul observer on the wall (" + net.ecmoons.client.ability.WispClient.count + ")",
                    net.ecmoons.client.ability.WispClient.count == 1);
            shot("wisp_1_observer_placed");
            ClientPlayerEntity p = player();
            p.yRot = 90F; // look back west at the body
            p.yRotO = 90F;
            p.xRot = 15F;
            p.xRotO = 15F;
        });
        then(5, () -> {
            check("wisp: the body is drawn while the camera is on the wisp (" + net.ecmoons.client.ability.WispClient.lastBodyDrawn + ")",
                    net.ecmoons.client.ability.WispClient.lastBodyDrawn == 1);
            shot("wisp_2_body_seen_from_wisp");
            tap(); // the Moon Ability key returns to the body
        });
        waitFor(40, () -> Minecraft.getInstance().getCameraEntity() == player());
        then(5, () -> {
            check("wisp: the Moon Ability key returns the camera to the body and removes the wisp",
                    Minecraft.getInstance().getCameraEntity() == player() && (wispCam.removed || Minecraft.getInstance().level.getEntity(wispCam.getId()) == null));
            tap(); // open the menu again
        });
        waitFor(40, () -> menu() != null);
        then(2, () -> {
            net.ecmoons.client.ability.WispMenuScreen m = menu();
            check("wisp: the menu lists observer 1 in range", m != null && m.data.numbers.length == 1 && m.data.inRange[0]);
            check("wisp: Pilot Wisp is not offered during the cooldown", m != null && m.data.cooldown > 0);
            shot("wisp_3_menu_observer");
            net.ecmoons.client.ability.WispClient.send(net.ecmoons.feature.ability.WispAbility.VIEW, 1);
        });
        waitFor(40, () -> Minecraft.getInstance().getCameraEntity() instanceof net.ecmoons.feature.ability.wisp.ObserverEntity);
        then(2, () -> {
            net.minecraft.entity.Entity cam = Minecraft.getInstance().getCameraEntity();
            check("wisp: viewing switches the camera to the observer", cam instanceof net.ecmoons.feature.ability.wisp.ObserverEntity);
            ClientPlayerEntity p = player();
            p.yRot = (cam == null ? 0 : cam.yRot) + 150F; // try to turn far away
            p.yRotO = p.yRot;
        });
        then(2, () -> {
            net.minecraft.entity.Entity cam = Minecraft.getInstance().getCameraEntity();
            float d = cam == null ? 999 : net.minecraft.util.math.MathHelper.wrapDegrees(player().yRot - cam.yRot);
            check(String.format("wisp: the observer view only turns a limited amount (%.0f degrees)", d), Math.abs(d) <= 60.01F);
            shot("wisp_4_observer_view");
            onServer(server -> server.getPlayerList().getPlayers().get(0).hurt(net.minecraft.util.DamageSource.GENERIC, 1F));
        });
        waitFor(40, () -> Minecraft.getInstance().getCameraEntity() == player());
        then(2, () -> {
            check("wisp: damage returns the camera to the body at once", Minecraft.getInstance().getCameraEntity() == player()
                    && !net.ecmoons.client.ability.WispClient.away());
            cmd("ecmoons ability reset @p");
            tap();
        });
        waitFor(40, () -> menu() != null);
        then(2, () -> net.ecmoons.client.ability.WispClient.send(net.ecmoons.feature.ability.WispAbility.PILOT, 0));
        waitFor(40, () -> Minecraft.getInstance().getCameraEntity() instanceof net.ecmoons.feature.ability.wisp.WispEntity);
        then(5, () -> cmd("ecmoons end"));
        waitFor(100, () -> Minecraft.getInstance().getCameraEntity() == player());
        then(10, () -> {
            boolean left = !Minecraft.getInstance().level.getEntitiesOfClass(net.minecraft.entity.Entity.class, player().getBoundingBox().inflate(60),
                    e -> e instanceof net.ecmoons.feature.ability.wisp.WispEntity || e instanceof net.ecmoons.feature.ability.wisp.ObserverEntity).isEmpty();
            check("wisp: when the moon ends the camera returns and no wisp or observer is left", Minecraft.getInstance().getCameraEntity() == player()
                    && !net.ecmoons.client.ability.WispClient.away() && !left);
            cmd("fill " + (ax + 6) + " " + ay + " " + (az - 3) + " " + (ax + 6) + " " + (ay + 4) + " " + (az + 3) + " air");
        });
    }

    private static Object patchouli() throws ReflectiveOperationException {
        return Class.forName("vazkii.patchouli.api.PatchouliAPI").getMethod("get").invoke(null);
    }

    private static void openGuide(String entry, int page) {
        try {
            net.minecraft.util.ResourceLocation book = new net.minecraft.util.ResourceLocation("ecmoons", "field_guide");
            Object api = patchouli();
            if (entry == null) api.getClass().getMethod("openBookGUI", net.minecraft.util.ResourceLocation.class).invoke(api, book);
            else api.getClass().getMethod("openBookEntry", net.minecraft.util.ResourceLocation.class, net.minecraft.util.ResourceLocation.class, int.class)
                    .invoke(api, book, new net.minecraft.util.ResourceLocation("ecmoons", entry), page);
        } catch (Throwable t) {
            check("field guide: open " + entry + " (" + t + ")", false);
        }
    }

    /** The Patchouli Lunar Field Guide (Patchouli is on the test runtime classpath). */
    private static void fieldGuide() {
        then(5, () -> {
            if (!net.minecraftforge.fml.ModList.get().isLoaded("patchouli")) {
                check("field guide: Patchouli present in the test environment", false);
                return;
            }
            try {
                Class<?> reg = Class.forName("vazkii.patchouli.common.book.BookRegistry");
                Object inst = reg.getField("INSTANCE").get(null);
                java.util.Map<?, ?> books = (java.util.Map<?, ?>) reg.getField("books").get(inst);
                Object book = books.get(new net.minecraft.util.ResourceLocation("ecmoons", "field_guide"));
                check("field guide: Patchouli registered the book", book != null);
                Object contents = book.getClass().getField("contents").get(book);
                java.util.Map<?, ?> entries = (java.util.Map<?, ?>) contents.getClass().getField("entries").get(contents);
                java.util.Map<?, ?> cats = (java.util.Map<?, ?>) contents.getClass().getField("categories").get(contents);
                check("field guide: 3 categories and 23 entries loaded (" + cats.size() + ", " + entries.size() + ")", cats.size() == 3 && entries.size() == 23);
            } catch (Throwable t) {
                check("field guide: reading the book contents (" + t + ")", false);
            }
            Minecraft.getInstance().options.hideGui = false;
            openGuide(null, 0);
        });
        then(20, () -> {
            check("field guide: book screen open (" + Minecraft.getInstance().screen + ")", Minecraft.getInstance().screen != null);
            shot("guide_0_landing");
            openGuide("moon_ability_key", 0);
        });
        then(20, () -> {
            shot("guide_1_ability_key");
            openGuide("frame_step", 0);
        });
        then(20, () -> {
            shot("guide_2_frame_step");
            openGuide("frame_step", 2);
        });
        then(20, () -> {
            shot("guide_3_frame_step_rules");
            openGuide("shell", 0);
        });
        then(20, () -> {
            shot("guide_4_shell");
            openGuide("shell", 2);
        });
        then(20, () -> {
            shot("guide_5_shell_rules");
            openGuide("revealing_moon", 0);
        });
        then(20, () -> {
            shot("guide_6_revealing");
            openGuide("war_moon", 0);
        });
        then(20, () -> {
            shot("guide_7_war");
            Minecraft.getInstance().setScreen(null);
        });
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
