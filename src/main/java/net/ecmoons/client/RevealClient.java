package net.ecmoons.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.config.Settings;
import net.ecmoons.net.RevealPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Revealing Moon on the client: the eyelid animation of the moon (open, partly closed, closed, reopening) and the
 * spectral eye markers. Timing of scans comes from the server (RevealPacket); idle blinks are local and random.
 * The eye frames are pre-drawn textures with the iris in the same place, so a blink never shifts the eye.
 */
public final class RevealClient {
    private static final ResourceLocation[] FRAMES = {
            MoonType.REVEALING.moonTexture(),
            new ResourceLocation(EcMoons.MOD_ID, "textures/environment/revealing_moon_blink1.png"),
            new ResourceLocation(EcMoons.MOD_ID, "textures/environment/revealing_moon_blink2.png"),
            new ResourceLocation(EcMoons.MOD_ID, "textures/environment/revealing_moon_blink3.png")};
    private static final ResourceLocation MARKER = new ResourceLocation(EcMoons.MOD_ID, "textures/gui/reveal_marker.png");
    private static final Random RANDOM = new Random();

    private static final ResourceLocation[] LOOKS = {
            MoonType.REVEALING.moonTexture(),
            new ResourceLocation(EcMoons.MOD_ID, "textures/environment/revealing_moon_look_l.png"),
            new ResourceLocation(EcMoons.MOD_ID, "textures/environment/revealing_moon_look_r.png"),
            new ResourceLocation(EcMoons.MOD_ID, "textures/environment/revealing_moon_look_u.png")};

    /** Eye phases: idle (with blinks and a slowly wandering pupil), warning/opening (the eyelids narrow while it
     *  focuses, then open wide, glow building), open (fully open with its strong glow: the active scan, when Binding
     *  Gaze can happen), recovery (at scan completion it slowly closes, then reopens into idle). */
    public static final int NONE = 0, BLINK = 1, WARN = 2, OPEN = 3, RECOVER = 4;
    private static final int RECOVER_CLOSE = 30, RECOVER_HOLD = 16, RECOVER_OPEN = 20;
    private static int anim = NONE;
    private static float animStart, warnStart, activeAt, scanAt, openStart;
    private static float nextIdleBlink = -1, nextLook = -1;
    private static int look;
    private static long clientTicks;
    private static ClientWorld lastWorld;
    private static int scanId = -1;
    /** Game time (shared by every client of the dimension) at which the current ripple started, or -1. */
    private static long rippleStart = -1;
    /** Ripples started on this client since launch (tests: one per scan, never per player or on a late join). */
    public static int ripplesStarted;

    // your own reveal (from the server's authoritative timer)
    private static float revealFrom = -1, revealUntil = -1, revealTotal = 1;
    // Binding Gaze
    private static boolean bound;

    private static final List<Vector3d> markers = new ArrayList<>();
    private static float markersUntil, markersFrom;
    /** Markers drawn in the last rendered frame (diagnostics / client test). */
    public static volatile int lastDrawn;
    private static final boolean DEBUG = Boolean.getBoolean("ecmoons.clienttest");

    private RevealClient() {}

    private static float now(float partialTicks) {
        return clientTicks + partialTicks;
    }

    private static long gameTime() {
        ClientWorld level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getGameTime();
    }

    // ---- packets ------------------------------------------------------------------------------------------------

    public static void onPacket(RevealPacket p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastWorld) { // a packet for a new world/dimension may arrive before the next client tick
            lastWorld = mc.level;
            reset();
        }
        float now = now(0);
        long gt = gameTime();
        switch (p.stage) {
            case RevealPacket.WARN:
                startWarn(now - Math.max(0, gt - p.time), p.a, p.b, p.scanId);
                break;
            case RevealPacket.SCAN: {
                scanId = p.scanId;
                markers.clear();
                markers.addAll(p.markers);
                markersFrom = now;
                markersUntil = now + p.ticks;
                look = 0;
                if (anim != BLINK) startRecover(now); // the scan is complete: the eye begins to close
                long age = Math.max(0, gt - p.time);
                startRipple(p.time, age);
                if (age < 10 && net.ecmoons.client.atmo.Atmosphere.on("enableLightTint")
                        && net.ecmoons.client.atmo.AtmoProfile.of(MoonType.REVEALING).flag("scanRing")) {
                    net.ecmoons.client.atmo.Atmosphere.pulse(0.45f, 30, new float[]{1f, 0.86f, 0.55f});
                }
                break;
            }
            case RevealPacket.SYNC:
                scanId = p.scanId;
                if (p.time >= 0 && gt < p.time + p.b) startWarn(now - Math.max(0, gt - p.time), p.a, p.b, p.scanId);
                if (p.time2 >= 0) startRipple(p.time2, Math.max(0, gt - p.time2)); // caught up, or nothing if over
                break;
            case RevealPacket.REVEALED:
                if (p.a <= 0) {
                    endReveal();
                } else {
                    if (revealUntil < 0 || now >= revealUntil) revealFrom = now; // a refresh never restarts the ease-in
                    revealUntil = now + p.a;
                    revealTotal = Math.max(1, p.b);
                }
                break;
            case RevealPacket.BIND:
                bound = p.a == 1;
                break;
            case RevealPacket.BLINK:
                startBlink(now);
                break;
            default:
                reset();
                break;
        }
    }

    private static void startWarn(float start, int opening, int countdown, int id) {
        scanId = id;
        warnStart = start;
        activeAt = start + Math.max(1, opening);
        scanAt = start + Math.max(1, countdown);
        anim = now(0) >= activeAt ? OPEN : WARN;
        openStart = activeAt;
        look = 0;
    }

    /** Start (or catch up with) the ripple that began at this game time; a finished ripple is never replayed. */
    private static void startRipple(long start, long age) {
        if (age >= rippleTicks() || start == rippleStart) return;
        rippleStart = start;
        ripplesStarted++;
    }

    private static float rippleTicks() {
        return (float) Math.max(10.0, Settings.client().d("revealing.rippleSeconds") * 20.0);
    }

    /** Preview demo: a full warning + scan sequence with no markers (visual only, nothing is scanned). */
    public static void demo(int warnTicks) {
        int opening = Math.max(10, warnTicks - 60);
        onPacket(RevealPacket.warn(-1, gameTime(), opening, warnTicks));
        demoScanAt = now(0) + warnTicks;
    }

    private static float demoScanAt = -1;

    private static void startBlink(float now) {
        anim = BLINK;
        animStart = now;
    }

    public static void reset() {
        anim = NONE;
        rippleStart = -1;
        nextIdleBlink = -1;
        demoScanAt = -1;
        look = 0;
        markers.clear();
        endReveal();
        bound = false;
        RevealHud.reset();
    }

    private static void endReveal() {
        revealFrom = revealUntil = -1;
        RevealHud.hidePanel();
    }

    public static int phase() {
        return anim;
    }

    public static int scanId() {
        return scanId;
    }

    // ---- your own reveal and Binding Gaze -------------------------------------------------------------------------

    /** True while you are revealed (your own server timer is running). */
    public static boolean revealed() {
        return revealUntil >= 0 && now(0) < revealUntil;
    }

    /** Ticks left of your reveal (0 = not revealed). */
    public static float revealRemaining(float partialTicks) {
        return revealUntil < 0 ? 0f : Math.max(0f, revealUntil - now(partialTicks));
    }

    /**
     * Strength of "you have been seen" (amber edges, colour loss): eases in over about 0.15 s, then follows the
     * remaining fraction of your own timer smoothly down to exactly 0 when it ends.
     */
    public static float revealIntensity(float partialTicks) {
        if (revealUntil < 0) return 0f;
        float now = now(partialTicks);
        float left = revealUntil - now;
        if (left <= 0f) return 0f;
        float ease = Math.min(1f, (now - revealFrom) / 3f);
        ease = ease * ease * (3 - 2 * ease);
        return ease * Math.min(1f, left / revealTotal);
    }

    /** Binding Gaze: movement is held (until you look away; released at once locally, confirmed by the server). */
    public static boolean bound() {
        return bound;
    }

    // ---- eye --------------------------------------------------------------------------------------------------------

    private static float warnProgress(float now) {
        return Math.max(0f, Math.min(1f, (now - warnStart) / Math.max(1f, activeAt - warnStart)));
    }

    private static float smooth(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3 - 2 * x);
    }

    /** 0 = fully open, 1 = fully shut. */
    public static float closedness(float partialTicks) {
        float now = now(partialTicks);
        float c = Settings.client().i("revealing.blinkCloseTicks");
        float h = Settings.client().i("revealing.blinkHoldTicks");
        switch (anim) {
            case BLINK: {
                float t = now - animStart;
                if (t < c) return t / c;
                if (t < c + h) return 1f;
                if (t < 2 * c + h) return 1f - (t - c - h) / c;
                return 0f;
            }
            case WARN: {
                // the eyelids narrow while it focuses, then open wide for the active scan
                float p = warnProgress(now);
                return 0.42f * (p < 0.5f ? smooth(p / 0.5f) : 1f - smooth((p - 0.5f) / 0.5f));
            }
            case RECOVER: {
                float t = now - animStart;
                if (t < RECOVER_CLOSE) return t / RECOVER_CLOSE;
                if (t < RECOVER_CLOSE + RECOVER_HOLD) return 1f;
                return Math.max(0f, 1f - (t - RECOVER_CLOSE - RECOVER_HOLD) / RECOVER_OPEN);
            }
            default:
                return 0f;
        }
    }

    /** Which pre-drawn frame to show: 0 open, 1 and 2 partly closed, 3 shut. */
    public static int frame(float partialTicks) {
        float k = closedness(partialTicks);
        if (k < 0.19f) return 0;
        if (k < 0.55f) return 1;
        if (k < 0.86f) return 2;
        return 3;
    }

    public static ResourceLocation eyeTexture(float partialTicks) {
        int f = frame(partialTicks);
        return f == 0 ? LOOKS[look] : FRAMES[f];
    }

    /** Amber halo strength around the eye (0..1): builds while it opens, full while scanning, fades as it closes. */
    public static float haloAmount(float partialTicks) {
        float now = now(partialTicks);
        switch (anim) {
            case WARN: return warnProgress(now);
            case OPEN: return 1f;
            case RECOVER: return Math.max(0f, 1f - (now - animStart) / 50f);
            default: return 0f;
        }
    }

    /** Iris brightening for the detail layer (0..1). */
    public static float irisBoost(float partialTicks) {
        return anim == WARN || anim == OPEN ? haloAmount(partialTicks) : 0f;
    }

    /**
     * The golden ripple: 0..1 while it crosses the sky, -1 when there is none. Computed from the dimension's game
     * time, so every client shows the same progress and a late client joins it part-way.
     */
    public static float ringProgress(float partialTicks) {
        if (rippleStart < 0) return -1f;
        ClientWorld level = Minecraft.getInstance().level;
        if (level == null) return -1f;
        float p = (level.getGameTime() - rippleStart + partialTicks) / rippleTicks();
        if (p < 0f) p = 0f; // the packet arrived a tick before this client's clock: start now
        return p < 1f ? p : -1f;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastWorld) {
            lastWorld = mc.level;
            reset();
        }
        if (mc.level == null || mc.isPaused()) return;
        clientTicks++;
        float now = clientTicks;
        float c = Settings.client().i("revealing.blinkCloseTicks");
        float h = Settings.client().i("revealing.blinkHoldTicks");
        if (demoScanAt >= 0 && now >= demoScanAt) {
            demoScanAt = -1;
            onPacket(RevealPacket.scan(-1, gameTime(), 100, java.util.Collections.emptyList()));
        }
        // advance the eye
        if (anim == BLINK && now - animStart > 2 * c + h) anim = NONE;
        if (anim == WARN && now >= activeAt) {
            anim = OPEN; // fully open: the active scan
            openStart = now;
        }
        if ((anim == WARN || anim == OPEN) && now > scanAt + 60) startRecover(now); // the completion never came
        if (anim == RECOVER && now - animStart > RECOVER_CLOSE + RECOVER_HOLD + RECOVER_OPEN) {
            anim = NONE;
            nextIdleBlink = now + idleGap();
        }
        if (rippleStart >= 0 && mc.level.getGameTime() - rippleStart > rippleTicks()) rippleStart = -1;
        if (!markers.isEmpty() && now > markersUntil) markers.clear();
        // your own reveal ends on its own timer (the server also says so); death clears it at once
        if (revealUntil >= 0 && (now >= revealUntil || mc.player == null || !mc.player.isAlive())) endReveal();
        // Binding Gaze: looking away releases at once (the server confirms a moment later)
        if (bound && mc.player != null) {
            double cone = Settings.client().d("revealing_binding.coneDegrees") + 1.0;
            if (!net.ecmoons.feature.reveal.MoonGaze.lookingAtMoon(mc.player, net.ecmoons.feature.reveal.MoonGaze.moonDirection(mc.level, 1f), cone)
                    || !mc.player.isAlive()) bound = false;
        }
        RevealHud.tick(mc);
        // idle behaviour while the eye is in the sky
        if (MoonSkyRenderer.visibleMoon(mc.level) != MoonType.REVEALING) {
            nextIdleBlink = -1;
            return;
        }
        if (nextIdleBlink < 0) nextIdleBlink = now + idleGap();
        if (anim == NONE && now >= nextIdleBlink) {
            startBlink(now);
            nextIdleBlink = now + idleGap();
        }
        if (anim == NONE && now >= nextLook) { // the pupil slowly wanders: it is watching
            look = RANDOM.nextInt(5) == 0 ? 0 : RANDOM.nextInt(LOOKS.length);
            nextLook = now + 100 + RANDOM.nextInt(140);
        }
    }

    private static void startRecover(float now) {
        anim = RECOVER;
        animStart = now;
    }

    private static float idleGap() {
        int min = Settings.client().i("revealing.idleBlinkMinSeconds"), max = Math.max(min, Settings.client().i("revealing.idleBlinkMaxSeconds"));
        return (min + RANDOM.nextFloat() * (max - min)) * 20f;
    }

    // ---- markers ------------------------------------------------------------------------------------------------------

    public static boolean scanning() {
        return anim == WARN || anim == OPEN;
    }

    public static int markerCount() {
        return markers.size();
    }

    public static List<Vector3d> markers() {
        return new ArrayList<>(markers);
    }

    static String direction(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        if (deg < 0) deg += 360;
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return names[(int) Math.round(deg / 45.0) % 8];
    }

    static String distance(double d) {
        return "~" + Math.max(10, Math.round(d / 10.0) * 10) + " m";
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderWorldLastEvent event) {
        lastDrawn = 0;
        if (markers.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float pt = event.getPartialTicks();
        float now = now(pt);
        Vector3d cam = mc.gameRenderer.getMainCamera().getPosition();
        MatrixStack ms = event.getMatrixStack();
        FontRenderer font = mc.font;
        float fadeIn = Math.min(1f, (now - markersFrom) / 6f);
        float fadeOut = Math.min(1f, (markersUntil - now) / 20f);
        float alpha = Math.max(0f, Math.min(fadeIn, fadeOut)) * (0.8f + 0.2f * (float) Math.sin(now * 0.3));
        if (DEBUG && clientTicks % 20 == 0) EcMoons.LOGGER.info("[ECMOONS-CLIENTTEST] reveal render: " + markers.size() + " markers, alpha " + alpha
                + ", now " + now + ", until " + markersUntil + ", cam " + mc.gameRenderer.getMainCamera().getPosition() + ", first " + markers.get(0));
        if (alpha <= 0.02f) return;

        RenderSystem.pushMatrix();
        mc.gameRenderer.lightTexture().turnOffLightLayer(); // markers glow: no night-time lightmap darkening
        RenderSystem.disableLighting();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.disableFog();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableTexture();
        IRenderTypeBuffer.Impl buffers = mc.renderBuffers().bufferSource();
        for (Vector3d m : markers) {
            Vector3d target = m.add(0, 1.4, 0);
            Vector3d rel = target.subtract(cam);
            double dist = rel.length();
            if (dist < 0.5) continue;
            double shown = Math.min(dist, 48.0);
            Vector3d pos = rel.scale(shown / dist);
            float size = (float) (Math.max(1.4, dist * 0.055) * shown / dist);

            ms.pushPose();
            ms.translate(pos.x, pos.y, pos.z);
            ms.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            ms.scale(-size, -size, size);
            Matrix4f mat = ms.last().pose();
            mc.getTextureManager().bind(MARKER);
            RenderSystem.color4f(1f, 0.85f, 0.45f, alpha);
            BufferBuilder b = Tessellator.getInstance().getBuilder();
            b.begin(7, DefaultVertexFormats.POSITION_TEX);
            b.vertex(mat, -0.5f, -0.5f, 0).uv(0, 0).endVertex();
            b.vertex(mat, -0.5f, 0.5f, 0).uv(0, 1).endVertex();
            b.vertex(mat, 0.5f, 0.5f, 0).uv(1, 1).endVertex();
            b.vertex(mat, 0.5f, -0.5f, 0).uv(1, 0).endVertex();
            Tessellator.getInstance().end();
            RenderSystem.color4f(1f, 1f, 1f, 1f);

            double hdx = m.x - mc.player.getX(), hdz = m.z - mc.player.getZ();
            String label = distance(Math.sqrt(hdx * hdx + hdz * hdz)) + " " + direction(hdx, hdz);
            float textScale = 1f / 24f;
            ms.translate(0, 0.6, 0);
            ms.scale(textScale, textScale, textScale);
            int a = Math.max(4, Math.min(255, (int) (alpha * 255)));
            font.drawInBatch(label, -font.width(label) / 2f, 0, (a << 24) | 0xFFD27A, false, ms.last().pose(), buffers, true,
                    (int) (alpha * 0.4f * 255) << 24, 0xF000F0);
            buffers.endBatch();
            ms.popPose();
            lastDrawn++;
        }
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.enableFog();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.popMatrix();
    }
}
