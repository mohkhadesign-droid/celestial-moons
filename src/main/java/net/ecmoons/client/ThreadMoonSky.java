package net.ecmoons.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.client.atmo.AtmoProfile;
import net.ecmoons.client.atmo.Atmosphere;
import net.ecmoons.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Matrix4f;

import java.util.Random;

/**
 * The Thread Moon's sky: the black cat on the yarn moon and the moon's own threads.
 * <p>
 * The cat is an animated overlay on the existing yarn moon (the moon texture is unchanged): frames cut from the
 * supplied cat animation (tools/art/build_thread_cat.py), drawn at twice the moon's pixel density on the same quad,
 * shaded with the same lunar phase as the yarn. It idles quietly (stands, tail swaying) and plays one short reaction
 * when the server's shared reaction stream says so - the same reaction, at the same moment, for everyone in the
 * dimension (game-time timestamps: a late packet joins part-way, an old one never restarts, a newer one is never
 * interrupted by an older one). Reactions end standing, so the idle loop resumes without a jump.
 * <ul>
 *   <li>Lifeline: kneads the yarn; one rose pulse runs along a moon strand.</li>
 *   <li>Guard Thread: digs in and braces; a thick strand tightens and catches a highlight.</li>
 *   <li>Knotbind: hooks two loose ends, bites and tugs; a tiny knot tightens.</li>
 *   <li>Last Stitch: a sharp tug, a thick strand flares rose-gold, knots and snaps; a short tumble, a few fibres drift
 *       away; the moon gives one soft rose-gold pulse (about 0.5 s).</li>
 * </ul>
 * Every few tens of seconds a short woven sequence unwinds from the moon. All of it is sky rendering on the moon's own
 * plane - no entities or world particles - and the moon is never left damaged: strands settle back as the cat idles.
 */
public final class ThreadMoonSky {
    private static final ResourceLocation CAT = new ResourceLocation(EcMoons.MOD_ID, "textures/environment/thread_cat.png");
    // atlas (from build_thread_cat.py): 1000 x 414, cells 100 x 46 (tile 98 x 44 at 1,1), 10 per row
    static final int ATLAS_W = 1000, ATLAS_H = 414, CELL_W = 100, CELL_H = 46, TILE_W = 98, TILE_H = 44, COLS = 10;
    // where the strip sits on the 128 px moon phase cell
    static final float STRIP_X = 42.828f, STRIP_Y = 26.020f, STRIP_W = 49f, STRIP_H = 22f;
    static final int[] SEQ_IDLE = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
    static final int[] SEQ_LIFELINE = {29, 30, 31, 32, 33, 34, 35, 36, 35, 34, 33, 32, 31, 30, 29};
    static final int[] SEQ_GUARD = {16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
    static final int[] SEQ_KNOT = {35, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 48, 42, 35, 29};
    static final int[] SEQ_LAST = {53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81};
    static final float FRAME_TICKS = 80f / 50f; // the reference's 80 ms frames in game ticks

    public static final byte LIFELINE = 1, GUARD = 2, KNOT = 3, LAST_STITCH = 4;

    // reaction stream state (from ThreadReactionPacket)
    private static byte type, nextType;
    private static int seq = -1, nextSeq = -1;
    private static long start = -1, nextStart = -1, idleFrom;
    private static ClientWorld lastWorld;
    /** Diagnostics / tests. */
    public static volatile int lastFrame = -1, lastReaction, reactionsPlayed, fibresDrawn, catDrawn;
    public static volatile float lastPulse;

    private ThreadMoonSky() {}

    static int[] seqOf(byte t) {
        switch (t) {
            case LIFELINE: return SEQ_LIFELINE;
            case GUARD: return SEQ_GUARD;
            case KNOT: return SEQ_KNOT;
            case LAST_STITCH: return SEQ_LAST;
            default: return SEQ_IDLE;
        }
    }

    static float lengthTicks(byte t) {
        return seqOf(t).length * FRAME_TICKS;
    }

    /** A reaction from the server's shared stream. */
    public static void onReaction(byte t, int event, int s, long startTime) {
        ClientWorld level = Minecraft.getInstance().level;
        if (level == null) return;
        checkWorld(level);
        long now = level.getGameTime();
        if (s <= seq || s <= nextSeq) return;                       // old: never restarts or interrupts anything
        if (startTime + lengthTicks(t) <= now) return;              // already over: never replayed
        if (startTime > now) {                                      // starts at the next pose boundary
            nextType = t;
            nextSeq = s;
            nextStart = startTime;
            return;
        }
        begin(t, s, startTime);
    }

    private static void begin(byte t, int s, long startTime) {
        type = t;
        seq = s;
        start = startTime;
        reactionsPlayed++;
        lastReaction = t;
        if (t == LAST_STITCH || t == KNOT) spawnFibres(t == LAST_STITCH ? 9 : 3, startTime);
    }

    private static void checkWorld(ClientWorld level) {
        if (level != lastWorld) {
            lastWorld = level;
            type = nextType = 0;
            seq = nextSeq = -1;
            start = nextStart = -1;
            idleFrom = level.getGameTime();
            for (int i = 0; i < FIBRES; i++) fibre[i][0] = -1;
        }
    }

    /** The current reaction (0 = idle) at this moment. */
    public static byte current(float pt) {
        ClientWorld level = Minecraft.getInstance().level;
        if (level == null) return 0;
        update(level, level.getGameTime() + pt);
        return type;
    }

    private static void update(ClientWorld level, double now) {
        checkWorld(level);
        if (nextType != 0 && now >= nextStart) {
            byte t = nextType;
            int s = nextSeq;
            long st = nextStart;
            nextType = 0;
            nextSeq = -1;
            begin(t, s, st);
        }
        if (type != 0 && now >= start + lengthTicks(type)) {
            idleFrom = (long) Math.floor(start + lengthTicks(type));
            type = 0;
        }
    }

    /** Atlas frame index to show now. */
    static int frame(double now) {
        if (type != 0) {
            int[] sq = seqOf(type);
            int i = (int) Math.floor((now - start) / FRAME_TICKS);
            return sq[Math.max(0, Math.min(sq.length - 1, i))];
        }
        int i = (int) Math.floor((now - idleFrom) / FRAME_TICKS);
        return SEQ_IDLE[Math.floorMod(i, SEQ_IDLE.length)];
    }

    /** Progress 0..1 through the current reaction (-1 when idle). */
    static float progress(double now) {
        return type == 0 ? -1f : (float) Math.min(1.0, (now - start) / lengthTicks(type));
    }

    // ---- rendering (called with the celestial rotation on the stack, right after the moon) -------------------

    public static void render(MatrixStack ms, ClientWorld level, float pt, float size) {
        if (MoonSkyRenderer.visibleMoon(level) != MoonType.THREAD) return;
        AtmoProfile prof = AtmoProfile.of(MoonType.THREAD);
        float rain = 1f - level.getRainLevel(pt);
        if (rain <= 0.01f) return;
        double now = level.getGameTime() + pt;
        update(level, now);
        Matrix4f m = ms.last().pose();
        float sky = ClientConfig.THREAD_SKY.get().floatValue();
        boolean anim = Atmosphere.on("enableSkyAnimations");
        // woven sequence and reaction strands (additive: the moon's light)
        if (anim && sky > 0.01f) {
            RenderSystem.disableTexture();
            RenderSystem.disableCull(); // thin threads in any direction
            RenderSystem.shadeModel(7425);
            if (prof.flag("moonStrands")) woven(m, size, now, prof, sky * rain);
            reactionStrands(m, size, now, prof, sky * rain);
            fibres(m, size, now, sky * rain);
            RenderSystem.shadeModel(7424);
            RenderSystem.enableCull();
        }
        // Last Stitch: one soft rose-gold luminance pulse of the moon itself (~0.5 s, eased)
        float pulse = 0f;
        if (type == LAST_STITCH) {
            double t = (now - start) / 20.0; // seconds
            if (t >= 0.25 && t <= 0.75) pulse = (float) Math.sin((t - 0.25) / 0.5 * Math.PI);
        }
        lastPulse = pulse;
        if (pulse > 0.01f && sky > 0.01f) moonPulse(m, level, size, pulse * 0.5f * Math.min(1f, sky) * rain);
        // the cat (normal blending: a black cat is dark, not light)
        if (prof.flag("moonCat")) cat(m, level, size, now, rain);
        RenderSystem.disableTexture();
        RenderSystem.color4f(1f, 1f, 1f, rain);
    }

    /** Moon-cell coordinates (0..128, the moon texture's own pixels) to the moon's quad in the celestial frame. */
    private static float wx(float cx, float size) {
        return size - 2f * size * cx / 128f;
    }

    private static float wz(float cy, float size) {
        return -size + 2f * size * cy / 128f;
    }

    private static final float Y = -99.8f;

    /** The yarn's lit fraction for this phase at a moon-cell column (same curve as the moon texture's shading). */
    static float phaseShade(int phase, float cx) {
        float x = cx - 48f + 0.5f, w = 32f;
        float f;
        switch (phase) {
            case 0: f = 1f; break;
            case 4: return 0.12f;
            case 1: f = ramp(0.62f * w - x); break;
            case 2: f = ramp(0.40f * w - x); break;
            case 3: f = ramp(0.15f * w - x); break;
            case 5: f = ramp(x - 0.85f * w); break;
            case 6: f = ramp(x - 0.60f * w); break;
            default: f = ramp(x - 0.38f * w); break;
        }
        return 0.10f + 0.90f * f;
    }

    private static float ramp(float t) {
        return Math.max(0f, Math.min(1f, 0.5f + t / 5f));
    }

    private static void cat(Matrix4f m, ClientWorld level, float size, double now, float rain) {
        int f = frame(now);
        lastFrame = f;
        int col = f % COLS, row = f / COLS;
        float u0 = (col * CELL_W + 1) / (float) ATLAS_W, v0 = (row * CELL_H + 1) / (float) ATLAS_H;
        float du = TILE_W / (float) ATLAS_W, dv = TILE_H / (float) ATLAS_H;
        int phase = level.getMoonPhase();
        RenderSystem.enableTexture();
        RenderSystem.defaultBlendFunc();
        Minecraft.getInstance().getTextureManager().bind(CAT);
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);
        int slices = 14; // vertical slices carry the phase shading across the strip
        for (int i = 0; i < slices; i++) {
            float s0 = i / (float) slices, s1 = (i + 1) / (float) slices;
            float cx0 = STRIP_X + STRIP_W * s0, cx1 = STRIP_X + STRIP_W * s1;
            float c0 = phaseShade(phase, cx0), c1 = phaseShade(phase, cx1);
            float x0 = wx(cx0, size), x1 = wx(cx1, size), zt = wz(STRIP_Y, size), zb = wz(STRIP_Y + STRIP_H, size);
            float uu0 = u0 + du * s0, uu1 = u0 + du * s1;
            // wound like the moon quad (visible from inside the sky)
            buf.vertex(m, x1, Y, zb).uv(uu1, v0 + dv).color(c1, c1, c1, rain).endVertex();
            buf.vertex(m, x0, Y, zb).uv(uu0, v0 + dv).color(c0, c0, c0, rain).endVertex();
            buf.vertex(m, x0, Y, zt).uv(uu0, v0).color(c0, c0, c0, rain).endVertex();
            buf.vertex(m, x1, Y, zt).uv(uu1, v0).color(c1, c1, c1, rain).endVertex();
        }
        buf.end();
        WorldVertexBufferUploader.end(buf);
        RenderSystem.blendFuncSeparate(770, 1, 1, 0); // back to the sky's additive blending
        catDrawn++;
    }

    private static void moonPulse(Matrix4f m, ClientWorld level, float size, float a) {
        RenderSystem.enableTexture();
        Minecraft.getInstance().getTextureManager().bind(MoonType.THREAD.moonTexture());
        int phase = level.getMoonPhase();
        int col = phase % 4, row = phase / 4 % 2;
        float u0 = col / 4f, v0 = row / 2f, u1 = (col + 1) / 4f, v1 = (row + 1) / 2f;
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);
        buf.vertex(m, -size, Y, size).uv(u1, v1).color(1f, 0.78f, 0.52f, a).endVertex();
        buf.vertex(m, size, Y, size).uv(u0, v1).color(1f, 0.78f, 0.52f, a).endVertex();
        buf.vertex(m, size, Y, -size).uv(u0, v0).color(1f, 0.78f, 0.52f, a).endVertex();
        buf.vertex(m, -size, Y, -size).uv(u1, v0).color(1f, 0.78f, 0.52f, a).endVertex();
        buf.end();
        WorldVertexBufferUploader.end(buf);
        RenderSystem.disableTexture();
    }

    // ---- the moon's threads (moon-cell coordinates, additive) ----------------------------------------------------

    /** A thin thread on the moon plane along a quadratic curve p0 -> c -> p1, alpha a0 -> a1, width in cell px. */
    private static void curve(BufferBuilder buf, Matrix4f m, float size, float[] p0, float[] c, float[] p1, float w,
                              float r, float g, float b, float a0, float a1, float from, float to) {
        int n = 18;
        float px = 0, py = 0;
        for (int i = 0; i <= n; i++) {
            float u = from + (to - from) * i / n;
            float x = (1 - u) * (1 - u) * p0[0] + 2 * (1 - u) * u * c[0] + u * u * p1[0];
            float y = (1 - u) * (1 - u) * p0[1] + 2 * (1 - u) * u * c[1] + u * u * p1[1];
            if (i > 0) {
                float dx = x - px, dy = y - py, l = (float) Math.sqrt(dx * dx + dy * dy);
                if (l < 1e-4f) continue;
                float nx = -dy / l * w / 2, ny = dx / l * w / 2;
                float ua = from + (to - from) * (i - 1) / n;
                float aa = a0 + (a1 - a0) * (ua - from) / Math.max(1e-4f, to - from), ab = a0 + (a1 - a0) * (u - from) / Math.max(1e-4f, to - from);
                aa *= (float) Math.sin(Math.PI * Math.max(0.02, Math.min(0.98, ua)));
                ab *= (float) Math.sin(Math.PI * Math.max(0.02, Math.min(0.98, u)));
                buf.vertex(m, wx(px - nx, size), Y, wz(py - ny, size)).color(r, g, b, aa).endVertex();
                buf.vertex(m, wx(px + nx, size), Y, wz(py + ny, size)).color(r, g, b, aa).endVertex();
                buf.vertex(m, wx(x + nx, size), Y, wz(y + ny, size)).color(r, g, b, ab).endVertex();
                buf.vertex(m, wx(x - nx, size), Y, wz(y - ny, size)).color(r, g, b, ab).endVertex();
            }
            px = x;
            py = y;
        }
    }

    /** Every moonStrandIntervalSeconds: three pairs of interwoven threads unwind from the yarn and drift outward. */
    private static void woven(Matrix4f m, float size, double now, AtmoProfile p, float k) {
        double period = Math.max(5, p.value("moonStrandIntervalSeconds", 30)) * 20, len = p.value("moonStrandSeconds", 3.5) * 20;
        double ph = now % period;
        if (ph > len) return;
        float u = (float) (ph / len);
        float fade = Math.min(1f, Math.min(u * 4f, (1f - u) * 3f));
        float[] c1 = p.accent, c2 = p.glow;
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
        long cycle = (long) (now / period);
        Random r = new Random(cycle * 31);
        for (int i = 0; i < 3; i++) {
            float ang = (float) (r.nextFloat() * Math.PI * 2);
            float sx = 64 + (float) Math.cos(ang) * 16, sy = 64 + (float) Math.sin(ang) * 16;
            float reach = 10 + 22 * u;
            float ex = 64 + (float) Math.cos(ang) * (16 + reach), ey = 64 + (float) Math.sin(ang) * (16 + reach);
            float bend = (r.nextFloat() - 0.5f) * 14;
            float cx = (sx + ex) / 2 - (float) Math.sin(ang) * bend, cy = (sy + ey) / 2 + (float) Math.cos(ang) * bend;
            for (int side = 0; side < 2; side++) {
                float o = (side == 0 ? 1 : -1) * 1.6f * (float) Math.sin(u * Math.PI * 4 + i);
                float[] c = side == 0 ? c1 : c2;
                curve(buf, m, size, new float[]{sx, sy}, new float[]{cx - o * (float) Math.sin(ang), cy + o * (float) Math.cos(ang)},
                        new float[]{ex, ey}, 0.9f, c[0], c[1], c[2], 0.75f * k * fade, 0.25f * k * fade, 0f, 1f);
            }
        }
        buf.end();
        WorldVertexBufferUploader.end(buf);
    }

    /** The strands that belong to each reaction. */
    private static void reactionStrands(Matrix4f m, float size, double now, AtmoProfile p, float k) {
        if (type == 0) return;
        float u = progress(now);
        float env = Math.min(1f, Math.min(u * 6f, (1f - u) * 4f));
        float[] rose = p.accent;
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
        float[] a0 = {82, 50}, c0 = {100, 44}, a1 = {108, 66};    // a loose strand off the yarn's right shoulder
        switch (type) {
            case LIFELINE: {
                curve(buf, m, size, a0, c0, a1, 0.8f, rose[0], rose[1], rose[2], 0.35f * k * env, 0.2f * k * env, 0f, 1f);
                float head = Math.min(1f, u * 1.4f); // one rose pulse runs along it
                curve(buf, m, size, a0, c0, a1, 1.6f, 1f, 0.72f, 0.82f, 0.0f, 0.9f * k * env, Math.max(0f, head - 0.22f), Math.max(0.01f, head));
                break;
            }
            case GUARD: {
                float tight = Math.min(1f, u * 2.2f); // the thick strand tightens (its bow straightens) and catches a highlight
                float[] s0 = {52, 47}, e0 = {80, 47}, c = {66, 47 + 6 * (1 - tight)};
                curve(buf, m, size, s0, c, e0, 1.8f, rose[0], rose[1], rose[2], 0.45f * k * env, 0.45f * k * env, 0f, 1f);
                float hl = (float) Math.sin(Math.min(1f, u * 1.5f) * Math.PI);
                curve(buf, m, size, s0, c, e0, 0.9f, 1f, 0.9f, 0.95f, 0.6f * hl * k, 0.6f * hl * k, 0.35f, 0.65f);
                break;
            }
            case KNOT: { // two loose ends crossing near the cat's paw, a tiny knot pulling tight
                float cross = Math.min(1f, u * 1.8f);
                curve(buf, m, size, new float[]{60, 47}, new float[]{66, 40 - 4 * cross}, new float[]{72 - 6 * cross, 36}, 1.0f,
                        rose[0], rose[1], rose[2], 0.6f * k * env, 0.4f * k * env, 0f, 1f);
                curve(buf, m, size, new float[]{76, 47}, new float[]{70, 40 - 4 * cross}, new float[]{64 + 6 * cross, 36}, 1.0f,
                        rose[0], rose[1], rose[2], 0.6f * k * env, 0.4f * k * env, 0f, 1f);
                float knot = Math.max(0f, u * 2f - 0.6f);
                if (knot > 0) dot(buf, m, size, 68, 37.5f, 1.2f + 0.8f * (1 - Math.min(1f, knot)), 1f, 0.8f, 0.88f, Math.min(1f, knot) * 0.9f * k * env);
                break;
            }
            case LAST_STITCH: { // a thick strand flares rose-gold, catches into a bright knot, then snaps
                float flare = Math.min(1f, u * 3f), snap = u > 0.42f ? Math.min(1f, (u - 0.42f) * 3f) : 0f;
                float r = 1f, g = 0.72f + 0.1f * flare, b = 0.48f;
                float[] s0 = {50, 46}, e0 = {96, 30}, c = {74, 30};
                if (snap <= 0f) {
                    curve(buf, m, size, s0, c, e0, 2.2f, r, g, b, 0.85f * k * flare, 0.85f * k * flare, 0f, 1f);
                    dot(buf, m, size, 74, 34, 1.6f + flare, 1f, 0.86f, 0.62f, k * flare);
                } else { // the two halves recoil and fade
                    float rec = 6 * snap, fade = (1 - snap) * 0.8f * k;
                    curve(buf, m, size, s0, new float[]{c[0] - rec, c[1] + rec}, new float[]{70 - rec, 34 + rec}, 1.6f, r, g, b, fade, fade * 0.5f, 0f, 1f);
                    curve(buf, m, size, new float[]{78 + rec, 34 - rec}, new float[]{c[0] + rec, c[1] - rec}, e0, 1.6f, r, g, b, fade * 0.5f, fade, 0f, 1f);
                }
                break;
            }
            default:
        }
        buf.end();
        WorldVertexBufferUploader.end(buf);
    }

    private static void dot(BufferBuilder buf, Matrix4f m, float size, float cx, float cy, float rad, float r, float g, float b, float a) {
        buf.vertex(m, wx(cx + rad, size), Y, wz(cy + rad, size)).color(r, g, b, a).endVertex();
        buf.vertex(m, wx(cx - rad, size), Y, wz(cy + rad, size)).color(r, g, b, a).endVertex();
        buf.vertex(m, wx(cx - rad, size), Y, wz(cy - rad, size)).color(r, g, b, a).endVertex();
        buf.vertex(m, wx(cx + rad, size), Y, wz(cy - rad, size)).color(r, g, b, a).endVertex();
    }

    // ---- loose fibres around the moon (at most 12, ~2 s each, fully faded) ----------------------------------------

    static final int FIBRES = 12;
    private static final float[][] fibre = new float[FIBRES][6]; // start time, x, y, vx, vy, spin
    private static final Random R = new Random();

    private static void spawnFibres(int n, long at) {
        for (int k = 0, i = 0; i < FIBRES && k < n; i++) {
            if (fibre[i][0] >= 0 && lastWorld != null && lastWorld.getGameTime() - fibre[i][0] < 40) continue;
            float ang = (float) (-Math.PI / 2 + (R.nextFloat() - 0.5f) * 2.4f);
            fibre[i][0] = at;
            fibre[i][1] = 66 + (R.nextFloat() - 0.5f) * 14;
            fibre[i][2] = 40 + (R.nextFloat() - 0.5f) * 6;
            fibre[i][3] = (float) Math.cos(ang) * (0.25f + R.nextFloat() * 0.25f);
            fibre[i][4] = (float) Math.sin(ang) * (0.25f + R.nextFloat() * 0.25f);
            fibre[i][5] = R.nextFloat() * 6.28f;
            k++;
        }
    }

    private static void fibres(Matrix4f m, float size, double now, float k) {
        int drawn = 0;
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
        for (float[] f : fibre) {
            if (f[0] < 0) continue;
            double t = now - f[0];
            if (t < 0) continue;
            if (t > 40) {
                f[0] = -1;
                continue;
            }
            float u = (float) (t / 40.0);
            float x = f[1] + f[3] * (float) t, y = f[2] + f[4] * (float) t + 0.004f * (float) (t * t);
            float a = 0.7f * k * (1f - u) * (1f - u);
            float ang = f[5] + u * 3f, dx = (float) Math.cos(ang) * 1.6f, dy = (float) Math.sin(ang) * 1.6f;
            curve(buf, m, size, new float[]{x - dx, y - dy}, new float[]{x + dy * 0.5f, y - dx * 0.5f}, new float[]{x + dx, y + dy}, 0.5f,
                    1f, 0.8f, 0.86f, a, a, 0f, 1f);
            drawn++;
        }
        buf.end();
        WorldVertexBufferUploader.end(buf);
        fibresDrawn = drawn;
    }

    /** Fibres alive right now (tests: never more than 12). */
    public static int liveFibres() {
        int n = 0;
        for (float[] f : fibre) if (f[0] >= 0) n++;
        return n;
    }
}
