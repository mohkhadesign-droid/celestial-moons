package net.ecmoons.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.ecmoons.MoonType;
import net.ecmoons.client.atmo.AtmoProfile;
import net.ecmoons.client.atmo.Atmosphere;
import net.ecmoons.config.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Thread Moon on the client: a thin, softly glowing thread between bonded teammates and a small "Thread bond"
 * indicator above the hotbar. The links come from the server's own bond computation (same range, team and
 * line-of-sight rules as the damage reduction) and are only ever sent to members of that team, so nobody else sees
 * them and the client does no distance or team checks of its own. Threads fade in when a bond forms, brighten as
 * teammates get closer and fade out over ~2.5 s when it breaks. They are depth-tested (never drawn through walls).
 */
public final class ThreadClient {
    private static float reduction;
    private static int[] links = new int[0];
    private static ClientWorld lastWorld;
    private static final Map<Long, float[]> fade = new HashMap<>(); // link -> {alpha, target, entityA, entityB}
    private static final int MAX_LINES = 8;
    public static int lastDrawnLines;

    private ThreadClient() {}

    public static void update(float r, int[] l) {
        reduction = r;
        links = l;
        for (float[] f : fade.values()) f[1] = 0f;
        for (int i = 0; i + 1 < l.length; i += 2) {
            long key = ((long) Math.min(l[i], l[i + 1]) << 32) | (Math.max(l[i], l[i + 1]) & 0xffffffffL);
            float[] f = fade.computeIfAbsent(key, k -> new float[4]);
            f[1] = 1f;
            f[2] = l[i];
            f[3] = l[i + 1];
        }
    }

    public static float reduction() {
        return reduction;
    }

    public static int linkCount() {
        return links.length / 2;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastWorld) {
            lastWorld = mc.level;
            update(0f, new int[0]);
            fade.clear();
        }
        if (mc.level == null || mc.isPaused()) return;
        float in = 1f / 30f, out = 1f / 50f; // ~1.5 s in, ~2.5 s out
        for (Iterator<float[]> it = fade.values().iterator(); it.hasNext(); ) {
            float[] f = it.next();
            f[0] = f[1] > f[0] ? Math.min(f[1], f[0] + in) : Math.max(f[1], f[0] - out);
            if (f[0] <= 0f && f[1] <= 0f) it.remove();
        }
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderWorldLastEvent event) {
        lastDrawnLines = 0;
        if (fade.isEmpty() || !Atmosphere.on("enableParticles") && !Atmosphere.on("enableSkyAnimations")) return;
        if (!AtmoProfile.of(MoonType.THREAD).flag("teamLines")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float pt = event.getPartialTicks();
        Vector3d cam = mc.gameRenderer.getMainCamera().getPosition();
        MatrixStack ms = event.getMatrixStack();
        Matrix4f m = ms.last().pose();
        float[] col = AtmoProfile.of(MoonType.THREAD).accent;
        double range = Settings.client().d("thread.bondRange");
        float t = (Atmosphere.ticks() + pt) / 20f;
        mc.gameRenderer.lightTexture().turnOffLightLayer();
        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.shadeModel(7425);
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
        int drawn = 0;
        for (float[] f : fade.values()) {
            if (drawn >= MAX_LINES || f[0] <= 0.01f) continue;
            Entity a = mc.level.getEntity((int) f[2]), b = mc.level.getEntity((int) f[3]);
            if (a == null || b == null) continue;
            Vector3d pa = a.getPosition(pt).add(0, a.getBbHeight() * 0.55, 0), pb = b.getPosition(pt).add(0, b.getBbHeight() * 0.55, 0);
            double len = pa.distanceTo(pb);
            if (len < 0.5) continue;
            float closeness = (float) Math.max(0.35, 1.0 - len / Math.max(1.0, range) * 0.65);
            float alpha = 0.42f * f[0] * closeness;
            int n = 16;
            Vector3d prev = null;
            float prevA = 0;
            for (int s = 0; s <= n; s++) {
                double u = s / (double) n;
                double sag = Math.sin(Math.PI * u) * Math.min(0.5, len * 0.035);
                double sway = Math.sin(Math.PI * u) * Math.sin(t * 1.3 + u * 4) * 0.06; // gently moving fibre
                Vector3d p = pa.add(pb.subtract(pa).scale(u)).add(sway, -sag, sway * 0.7).subtract(cam);
                float pa2 = alpha * (0.6f + 0.4f * (float) Math.sin(Math.PI * u));
                if (prev != null) quadSegment(buf, m, prev, p, 0.022, col, prevA, pa2);
                prev = p;
                prevA = pa2;
            }
            drawn++;
        }
        Tessellator.getInstance().end();
        lastDrawnLines = drawn;
        RenderSystem.shadeModel(7424);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
    }

    /** A thin camera-facing quad between two points (camera at the origin). */
    private static void quadSegment(BufferBuilder buf, Matrix4f m, Vector3d a, Vector3d b, double w, float[] c, float aa, float ab) {
        Vector3d d = b.subtract(a);
        Vector3d side = d.cross(a.add(b).scale(0.5)).normalize().scale(w);
        buf.vertex(m, (float) (a.x - side.x), (float) (a.y - side.y), (float) (a.z - side.z)).color(c[0], c[1], c[2], aa).endVertex();
        buf.vertex(m, (float) (a.x + side.x), (float) (a.y + side.y), (float) (a.z + side.z)).color(c[0], c[1], c[2], aa).endVertex();
        buf.vertex(m, (float) (b.x + side.x), (float) (b.y + side.y), (float) (b.z + side.z)).color(c[0], c[1], c[2], ab).endVertex();
        buf.vertex(m, (float) (b.x - side.x), (float) (b.y - side.y), (float) (b.z - side.z)).color(c[0], c[1], c[2], ab).endVertex();
    }

    @SubscribeEvent
    public static void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL || reduction <= 0.001f) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        MatrixStack ms = event.getMatrixStack();
        int w = event.getWindow().getGuiScaledWidth(), h = event.getWindow().getGuiScaledHeight();
        String text = "✧ Thread bond -" + Math.round(reduction * 100) + "%";
        mc.font.drawShadow(ms, text, w / 2f + 98, h - 14, 0xF2A0BE);
    }
}
