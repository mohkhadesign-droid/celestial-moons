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

    private static final int NONE = 0, BLINK = 1, SCAN = 2;
    private static int anim = NONE;
    private static float animStart, closeStart, openStart = -1, scanTimeout;
    private static float nextIdleBlink = -1;
    private static long clientTicks;
    private static ClientWorld lastWorld;

    private static final List<Vector3d> markers = new ArrayList<>();
    private static float markersUntil, markersFrom;

    private RevealClient() {}

    private static float now(float partialTicks) {
        return clientTicks + partialTicks;
    }

    // ---- packets ------------------------------------------------------------------------------------------------

    public static void onPacket(byte stage, int ticks, List<Vector3d> list) {
        float now = now(0);
        int close = Settings.client().i("revealing.blinkCloseTicks");
        switch (stage) {
            case RevealPacket.WARN:
                anim = SCAN;
                closeStart = now + Math.max(0, ticks - close);
                openStart = -1;
                scanTimeout = now + ticks + 60;
                break;
            case RevealPacket.SCAN:
                markers.clear();
                markers.addAll(list);
                markersFrom = now;
                markersUntil = now + ticks;
                if (anim == SCAN) {
                    openStart = Math.max(now, closeStart + close);
                } else {
                    startBlink(now);
                }
                break;
            case RevealPacket.BLINK:
                startBlink(now);
                break;
            default:
                reset();
                break;
        }
    }

    private static void startBlink(float now) {
        anim = BLINK;
        animStart = now;
    }

    public static void reset() {
        anim = NONE;
        openStart = -1;
        nextIdleBlink = -1;
        markers.clear();
    }

    // ---- eye --------------------------------------------------------------------------------------------------------

    /** 0 = fully open, 1 = fully shut. */
    public static float closedness(float partialTicks) {
        float now = now(partialTicks);
        float c = Settings.client().i("revealing.blinkCloseTicks");
        float h = Settings.client().i("revealing.blinkHoldTicks");
        if (anim == BLINK) {
            float t = now - animStart;
            if (t < c) return t / c;
            if (t < c + h) return 1f;
            if (t < 2 * c + h) return 1f - (t - c - h) / c;
            return 0f;
        }
        if (anim == SCAN) {
            if (now < closeStart) return 0f;
            if (openStart < 0 || now < openStart) return Math.min(1f, (now - closeStart) / c);
            return Math.max(0f, 1f - (now - openStart) / c);
        }
        return 0f;
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
        return FRAMES[frame(partialTicks)];
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
        // finish animations
        if (anim == BLINK && now - animStart > 2 * c + h) anim = NONE;
        if (anim == SCAN) {
            if (openStart < 0 && now > scanTimeout) openStart = now; // scan never came (moon ended / packet lost)
            if (openStart >= 0 && now > openStart + c) anim = NONE;
        }
        if (!markers.isEmpty() && now > markersUntil) markers.clear();
        // idle blinks while the eye is in the sky
        if (MoonSkyRenderer.visibleMoon(mc.level) != MoonType.REVEALING) {
            nextIdleBlink = -1;
            return;
        }
        if (nextIdleBlink < 0) nextIdleBlink = now + idleGap();
        if (anim == NONE && now >= nextIdleBlink) {
            startBlink(now);
            nextIdleBlink = now + idleGap();
        }
    }

    private static float idleGap() {
        int min = Settings.client().i("revealing.idleBlinkMinSeconds"), max = Math.max(min, Settings.client().i("revealing.idleBlinkMaxSeconds"));
        return (min + RANDOM.nextFloat() * (max - min)) * 20f;
    }

    // ---- markers ------------------------------------------------------------------------------------------------------

    public static int markerCount() {
        return markers.size();
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
        if (alpha <= 0.02f) return;

        RenderSystem.pushMatrix();
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
            float size = (float) (Math.max(0.9, dist * 0.04) * shown / dist);

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
            float textScale = 1f / 40f;
            ms.translate(0, 0.62, 0);
            ms.scale(textScale, textScale, textScale);
            int a = Math.max(4, Math.min(255, (int) (alpha * 255)));
            font.drawInBatch(label, -font.width(label) / 2f, 0, (a << 24) | 0xFFD27A, false, ms.last().pose(), buffers, true,
                    (int) (alpha * 0.4f * 255) << 24, 0xF000F0);
            buffers.endBatch();
            ms.popPose();
        }
        RenderSystem.enableFog();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.popMatrix();
    }
}
