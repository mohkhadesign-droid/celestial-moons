package net.ecmoons.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.client.ability.AbilityClient;
import net.ecmoons.client.atmo.AtmoProfile;
import net.ecmoons.client.atmo.Atmosphere;
import net.ecmoons.config.ClientConfig;
import net.ecmoons.config.Settings;
import net.ecmoons.net.Network;
import net.ecmoons.net.ThreadInputPacket;
import net.ecmoons.net.ThreadPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.AbstractClientPlayerEntity;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Thread Moon on the client.
 * <ul>
 *   <li>Input: the Moon Ability key (default V) - while knotted it unravels (stand still and hold); crouching and aiming
 *       at a connected teammate it channels Lifeline; otherwise a press throws Knotbind yarn. The Guard key (default G)
 *       selects / clears the aimed teammate. The client only asks; the server checks everything.</li>
 *   <li>Threads (depth-tested, never through terrain): your team's soft rose support threads (from the server, team only)
 *       with Lifeline pulses, a braided guard link, guard payment pulses and the Last Stitch knot; nearby Knotbind
 *       tethers - thicker, brighter, slack when close and taut at the limit, with a central knot that frays while
 *       someone unravels, and a few loose fibres when it comes apart. Nothing is drawn to an entity you can't see.</li>
 *   <li>A small contextual HUD near the hotbar/crosshair (text, colour and simple icons).</li>
 * </ul>
 */
public final class ThreadClient {
    public static final KeyBinding GUARD_KEY = new KeyBinding("key.ecmoons.thread_guard", KeyConflictContext.IN_GAME,
            InputMappings.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.ecmoons");

    private static ThreadPacket state = new ThreadPacket();
    private static long stateAt;
    private static int[] links = new int[0];
    private static ClientWorld lastWorld;
    private static final Map<Long, float[]> fade = new HashMap<>(); // link -> {alpha, target, entityA, entityB}
    private static final int MAX_LINES = 8;
    public static int lastDrawnLines, lastDrawnKnots, lastDrawnGuards;
    private static long ticks;
    // input
    private static boolean wasDown, guardWasDown;
    private static byte mode; // 0 none, 1 lifeline, 2 unravel
    private static long lastReset;
    public static String lastAction = "";
    // knots: id, a, b, ticksLeft, unravelA, unravelB
    private static int[] knots = new int[0];
    private static long knotsAt;
    // effects: kind, a, b, start tick
    private static final List<float[]> FX = new ArrayList<>();
    private static final List<float[]> LOOSE = new ArrayList<>(); // world fibres: x, y, z, vx, vy, vz, start
    private static final Random R = new Random();

    private ThreadClient() {}

    public static void register() {
        net.minecraftforge.fml.client.registry.ClientRegistry.registerKeyBinding(GUARD_KEY);
    }

    // ---- packets -------------------------------------------------------------------------------------------------

    public static void update(ThreadPacket p) {
        state = p;
        stateAt = ticks;
        setLinks(p.links);
    }

    /** Tests: a local state with just a bond value and links. */
    public static void update(float r, int[] l) {
        ThreadPacket p = new ThreadPacket();
        p.reduction = r;
        p.links = l;
        update(p);
    }

    private static void setLinks(int[] l) {
        links = l;
        for (float[] f : fade.values()) f[1] = 0f;
        for (int i = 0; i + 1 < l.length; i += 2) {
            long key = key(l[i], l[i + 1]);
            float[] f = fade.computeIfAbsent(key, k -> new float[4]);
            f[1] = 1f;
            f[2] = l[i];
            f[3] = l[i + 1];
        }
    }

    private static long key(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xffffffffL);
    }

    public static void onFx(byte kind, int a, int b, float value) {
        if (kind == 4) { // a knot came loose: a few fibres where it was
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            Entity ea = mc.level.getEntity(a), eb = mc.level.getEntity(b);
            if (ea == null || eb == null) return;
            Vector3d mid = anchor(mc, ea, 1f).add(anchor(mc, eb, 1f)).scale(0.5);
            for (int i = 0; i < 5 && LOOSE.size() < 24; i++) {
                LOOSE.add(new float[]{(float) mid.x, (float) mid.y, (float) mid.z, (R.nextFloat() - 0.5f) * 0.06f,
                        0.01f + R.nextFloat() * 0.03f, (R.nextFloat() - 0.5f) * 0.06f, ticks});
            }
            return;
        }
        if (FX.size() < 32) FX.add(new float[]{kind, a, b, ticks, value});
    }

    public static void onKnots(int[] data) {
        knots = data;
        knotsAt = ticks;
    }

    public static float reduction() {
        return state.reduction;
    }

    public static int linkCount() {
        return links.length / 2;
    }

    public static ThreadPacket state() {
        return state;
    }

    public static int[] knots() {
        return knots;
    }

    // ---- tick: fades and input -----------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastWorld) {
            lastWorld = mc.level;
            update(new ThreadPacket());
            fade.clear();
            knots = new int[0];
            FX.clear();
            LOOSE.clear();
            mode = 0;
        }
        if (mc.level == null || mc.isPaused()) return;
        ticks++;
        float in = 1f / 30f, out = 1f / 60f; // ~1.5 s in, the existing 3 s fade out
        for (Iterator<float[]> it = fade.values().iterator(); it.hasNext(); ) {
            float[] f = it.next();
            f[0] = f[1] > f[0] ? Math.min(f[1], f[0] + in) : Math.max(f[1], f[0] - out);
            if (f[0] <= 0f && f[1] <= 0f) it.remove();
        }
        FX.removeIf(f -> ticks - f[3] > 40);
        LOOSE.removeIf(f -> ticks - f[6] > 30);
        input(mc);
    }

    private static boolean threadMoon(Minecraft mc) {
        return mc.level != null && Moons.active(mc.level) == MoonType.THREAD;
    }

    private static void send(byte action, int target) {
        Network.CHANNEL.sendToServer(new ThreadInputPacket(action, target));
        lastAction = action + ":" + target;
    }

    private static void input(Minecraft mc) {
        if (mc.player == null) return;
        boolean moon = threadMoon(mc);
        boolean down = moon && ((AbilityClient.KEY.isDown() && mc.screen == null) || AbilityClient.forced());
        if (down && !wasDown) {
            int mate;
            if (state.unravel >= 0f) {
                send(ThreadInputPacket.UNRAVEL_START, -1);
                mode = 2;
            } else if (mc.player.isCrouching() && (mate = aimedPlayer(mc, Settings.client().d("thread_lifeline.range") + 0.5, true)) >= 0) {
                send(ThreadInputPacket.LIFELINE_START, mate);
                mode = 1;
            } else {
                send(ThreadInputPacket.KNOT_FIRE, -1);
                mode = 0;
            }
        } else if (!down && wasDown) {
            if (mode == 1) send(ThreadInputPacket.LIFELINE_STOP, -1);
            if (mode == 2) send(ThreadInputPacket.UNRAVEL_STOP, -1);
            mode = 0;
        } else if (down && mode == 2) {
            // voluntary movement, attacking or using an item restarts YOUR unravel (tether pulls don't: they are not input)
            boolean moving = mc.player.input.forwardImpulse != 0 || mc.player.input.leftImpulse != 0 || mc.player.input.jumping
                    || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown();
            if (moving && ticks - lastReset >= 4) {
                lastReset = ticks;
                send(ThreadInputPacket.UNRAVEL_RESET, -1);
            }
        }
        wasDown = down;
        boolean g = moon && GUARD_KEY.isDown() && mc.screen == null;
        if (g && !guardWasDown) send(ThreadInputPacket.GUARD_TOGGLE, aimedPlayer(mc, Settings.client().d("thread.bondRange"), true));
        guardWasDown = g;
    }

    /** Tests: press the Guard key once, aimed at whatever the player looks at. */
    public static void testGuard() {
        Minecraft mc = Minecraft.getInstance();
        send(ThreadInputPacket.GUARD_TOGGLE, aimedPlayer(mc, Settings.client().d("thread.bondRange"), true));
    }

    /** The player under your aim (eye ray, nearest, not behind blocks), optionally only bonded teammates; -1 if none. */
    static int aimedPlayer(Minecraft mc, double range, boolean teammatesOnly) {
        Vector3d eye = mc.player.getEyePosition(1f);
        Vector3d end = eye.add(mc.player.getViewVector(1f).scale(range));
        int best = -1;
        double bestD = Double.MAX_VALUE;
        Vector3d bestHit = null;
        for (AbstractClientPlayerEntity o : mc.level.players()) {
            if (o == mc.player || !o.isAlive() || o.isSpectator()) continue;
            if (teammatesOnly && !linkedTo(mc.player.getId(), o.getId())) continue;
            AxisAlignedBB box = o.getBoundingBox().inflate(0.2);
            Optional<Vector3d> hit = box.clip(eye, end);
            if (!hit.isPresent()) continue;
            double d = hit.get().distanceToSqr(eye);
            if (d < bestD) {
                bestD = d;
                best = o.getId();
                bestHit = hit.get();
            }
        }
        if (best < 0) return -1;
        RayTraceResult r = mc.level.clip(new RayTraceContext(eye, bestHit, RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, mc.player));
        return r.getType() == RayTraceResult.Type.MISS ? best : -1;
    }

    static boolean linkedTo(int me, int other) {
        for (int i = 0; i + 1 < links.length; i += 2) {
            if ((links[i] == me && links[i + 1] == other) || (links[i] == other && links[i + 1] == me)) return true;
        }
        return false;
    }

    // ---- world threads -------------------------------------------------------------------------------------------

    private static boolean visible(Minecraft mc, Entity e) {
        return e != null && (e == mc.player || !e.isInvisibleTo(mc.player));
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderWorldLastEvent event) {
        lastDrawnLines = lastDrawnKnots = lastDrawnGuards = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ClientConfig.THREAD_LINES.get()) return;
        boolean friendly = !fade.isEmpty() && AtmoProfile.of(MoonType.THREAD).flag("teamLines")
                && (Atmosphere.on("enableParticles") || Atmosphere.on("enableSkyAnimations"));
        if (!friendly && knots.length == 0 && LOOSE.isEmpty() && state.markTarget < 0) return;
        float pt = event.getPartialTicks();
        Vector3d cam = mc.gameRenderer.getMainCamera().getPosition();
        Matrix4f m = event.getMatrixStack().last().pose();
        float[] rose = AtmoProfile.of(MoonType.THREAD).accent;
        float[] lilac = AtmoProfile.of(MoonType.THREAD).glow;
        double range = Settings.client().d("thread.bondRange");
        float t = (Atmosphere.ticks() + pt) / 20f;
        float pulses = ClientConfig.THREAD_PULSES.get().floatValue();
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
        if (friendly) {
            for (Map.Entry<Long, float[]> en : fade.entrySet()) {
                float[] f = en.getValue();
                if (drawn >= MAX_LINES || f[0] <= 0.01f) continue;
                Entity a = mc.level.getEntity((int) f[2]), b = mc.level.getEntity((int) f[3]);
                if (!visible(mc, a) || !visible(mc, b)) continue;
                Vector3d pa = anchor(mc, a, pt), pb = anchor(mc, b, pt);
                double len = pa.distanceTo(pb);
                if (len < 0.5) continue;
                // lightly stretched and dimmer as it nears the 12-block limit
                float closeness = (float) Math.max(0.3, 1.0 - len / Math.max(1.0, range) * 0.7);
                float alpha = 0.3f * f[0] * closeness;
                boolean guard = guardPair(a.getId(), b.getId());
                double sag = Math.min(0.5, len * 0.035) * (1.0 - 0.6 * Math.max(0, len / range - 0.75) * 4);
                if (guard) { // a modest braid: two thin threads twisting around the line
                    braid(buf, m, pa, pb, cam, sag, t, rose, lilac, alpha * 1.2f);
                    lastDrawnGuards++;
                } else {
                    thread(buf, m, pa, pb, cam, sag, t, 0.022, rose, alpha, 0.06);
                }
                drawn++;
            }
            // pulses along friendly threads
            for (float[] fx : FX) {
                Entity from = mc.level.getEntity((int) fx[1]), to = mc.level.getEntity((int) fx[2]);
                if (!visible(mc, from) || !visible(mc, to) || pulses <= 0.01f) continue;
                float age = (ticks - fx[3] + pt) / 20f;
                Vector3d pa = anchor(mc, from, pt), pb = anchor(mc, to, pt);
                byte kind = (byte) fx[0];
                if (kind == 1 || kind == 2) { // Lifeline: donor -> recipient; Guard: target -> paying guardian
                    float u = Math.min(1f, age / 0.6f);
                    if (u >= 1f) continue;
                    Vector3d p = pa.add(pb.subtract(pa).scale(u)).subtract(cam);
                    float a = (float) Math.sin(u * Math.PI) * 0.9f * pulses;
                    float[] c = kind == 1 ? new float[]{1f, 0.62f, 0.74f} : new float[]{0.95f, 0.8f, 1f};
                    glow(buf, m, p, 0.09, c, a);
                } else if (kind == 3) { // Last Stitch: a rose-gold knot catches on the thread, tightens, unravels
                    float u = Math.min(1f, age / 1.6f);
                    if (u >= 1f) continue;
                    Vector3d mid = pa.add(pb).scale(0.5).subtract(cam);
                    float knotA = (float) Math.sin(u * Math.PI) * pulses;
                    glow(buf, m, mid, 0.16 * (1.2 - u * 0.5), new float[]{1f, 0.78f, 0.5f}, knotA);
                    thread(buf, m, pa, pb, cam, 0.0, t, 0.03, new float[]{1f, 0.75f, 0.5f}, 0.5f * knotA, 0.0);
                }
            }
        }
        lastDrawnLines = drawn;
        // Knotbind tethers (public: victims, caster and nearby observers)
        for (int i = 0; i + 5 < knots.length; i += 6) {
            Entity a = mc.level.getEntity(knots[i + 1]), b = mc.level.getEntity(knots[i + 2]);
            if (!visible(mc, a) || !visible(mc, b)) continue;
            Vector3d pa = anchor(mc, a, pt), pb = anchor(mc, b, pt);
            double len = pa.distanceTo(pb), max = Settings.client().d("thread_knotbind.tetherLength");
            float taut = (float) Math.min(1, Math.max(0, (len - max * 0.6) / (max * 0.4)));
            double sag = (1 - taut) * Math.min(0.9, len * 0.12);
            float bright = 0.45f + 0.35f * taut;
            float[] pink = {1f, 0.55f, 0.75f};
            thread(buf, m, pa, pb, cam, sag, t, 0.045 + 0.01 * taut, pink, bright, 0.0);
            float fray = Math.max(knots[i + 4], knots[i + 5]) / 100f;
            Vector3d mid = pa.add(pb).scale(0.5).add(0, -sag, 0).subtract(cam);
            glow(buf, m, mid, 0.11 * (1 - 0.4 * fray), new float[]{1f, 0.66f, 0.82f}, 0.9f);
            for (int k = 0; k < (int) (fray * 5); k++) { // the knot frays while someone unravels it
                double ang = k * 1.3 + t * 3;
                Vector3d tip = mid.add(Math.cos(ang) * 0.18 * fray, Math.sin(ang * 1.7) * 0.12 * fray, Math.sin(ang) * 0.18 * fray);
                segment(buf, m, mid, tip, 0.01, pink, 0.7f, 0.1f);
            }
            lastDrawnKnots++;
        }
        // loose fibres from a released knot
        for (float[] f : LOOSE) {
            float age = ticks - f[6] + pt, u = age / 30f;
            Vector3d p = new Vector3d(f[0] + f[3] * age, f[1] + f[4] * age, f[2] + f[5] * age).subtract(cam);
            segment(buf, m, p, p.add(0.12 * Math.cos(age * 0.3), 0.06, 0.12 * Math.sin(age * 0.3)), 0.01, new float[]{1f, 0.7f, 0.85f}, 0.6f * (1 - u), 0.6f * (1 - u));
        }
        // your first Knotbind mark: a small rose diamond above the target, only where you can see it (depth tested)
        if (state.markTarget >= 0 && ticks - stateAt < state.markTicks + 2) {
            Entity e = mc.level.getEntity(state.markTarget);
            if (visible(mc, e)) {
                Vector3d p = e.getPosition(pt).add(0, e.getBbHeight() + 0.45, 0).subtract(cam);
                glow(buf, m, p, 0.12, new float[]{1f, 0.5f, 0.72f}, 0.75f + 0.25f * (float) Math.sin(t * 8));
            }
        }
        Tessellator.getInstance().end();
        RenderSystem.shadeModel(7424);
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
    }

    private static boolean guardPair(int a, int b) {
        int[] g = state.guards;
        for (int i = 0; i + 2 < g.length; i += 3) {
            if ((g[i] == a && g[i + 1] == b) || (g[i] == b && g[i + 1] == a)) return g[i + 2] == 1;
        }
        return false;
    }

    /** A modestly curved thread from pa to pb (camera at the origin). */
    private static void thread(BufferBuilder buf, Matrix4f m, Vector3d pa, Vector3d pb, Vector3d cam, double sag, float t,
                               double w, float[] col, float alpha, double sway) {
        int n = 16;
        Vector3d prev = null;
        float prevA = 0;
        for (int s = 0; s <= n; s++) {
            double u = s / (double) n;
            double sg = Math.sin(Math.PI * u) * sag;
            double sw = Math.sin(Math.PI * u) * Math.sin(t * 1.3 + u * 4) * sway;
            Vector3d p = pa.add(pb.subtract(pa).scale(u)).add(sw, -sg, sw * 0.7).subtract(cam);
            float a = alpha * (0.6f + 0.4f * (float) Math.sin(Math.PI * u));
            if (prev != null) segment(buf, m, prev, p, w, col, prevA, a);
            prev = p;
            prevA = a;
        }
    }

    /** Two thin threads twisting around each other: the guard link. */
    private static void braid(BufferBuilder buf, Matrix4f m, Vector3d pa, Vector3d pb, Vector3d cam, double sag, float t,
                              float[] c1, float[] c2, float alpha) {
        Vector3d d = pb.subtract(pa);
        Vector3d side = d.cross(new Vector3d(0, 1, 0));
        if (side.lengthSqr() < 1e-6) side = new Vector3d(1, 0, 0);
        side = side.normalize();
        Vector3d up = side.cross(d).normalize();
        int n = 24;
        for (int k = 0; k < 2; k++) {
            Vector3d prev = null;
            float prevA = 0;
            for (int s = 0; s <= n; s++) {
                double u = s / (double) n;
                double ang = u * Math.PI * 2 * Math.max(2, d.length() / 1.5) + t * 2 + k * Math.PI;
                double r = 0.045 * Math.sin(Math.PI * u) + 0.012;
                Vector3d p = pa.add(d.scale(u)).add(0, -Math.sin(Math.PI * u) * sag, 0)
                        .add(side.scale(Math.cos(ang) * r)).add(up.scale(Math.sin(ang) * r)).subtract(cam);
                float a = alpha * (0.55f + 0.45f * (float) Math.sin(Math.PI * u)) * (0.75f + 0.25f * (float) Math.cos(ang));
                if (prev != null) segment(buf, m, prev, p, 0.016, k == 0 ? c1 : c2, prevA, a);
                prev = p;
                prevA = a;
            }
        }
    }

    /** Where a thread attaches: chest height, or for yourself in first person below the view (never over the crosshair). */
    static Vector3d anchor(Minecraft mc, Entity e, float pt) {
        if (e == mc.player && mc.options.getCameraType().isFirstPerson()) {
            Vector3d look = e.getViewVector(pt);
            return mc.gameRenderer.getMainCamera().getPosition().add(look.x * 0.9, look.y * 0.9 - 0.45, look.z * 0.9);
        }
        return e.getPosition(pt).add(0, e.getBbHeight() * 0.55, 0);
    }

    /** A thin camera-facing quad between two points (camera at the origin). */
    private static void segment(BufferBuilder buf, Matrix4f m, Vector3d a, Vector3d b, double w, float[] c, float aa, float ab) {
        Vector3d d = b.subtract(a);
        Vector3d side = d.cross(a.add(b).scale(0.5));
        if (side.lengthSqr() < 1e-9) return;
        side = side.normalize().scale(w);
        buf.vertex(m, (float) (a.x - side.x), (float) (a.y - side.y), (float) (a.z - side.z)).color(c[0], c[1], c[2], aa).endVertex();
        buf.vertex(m, (float) (a.x + side.x), (float) (a.y + side.y), (float) (a.z + side.z)).color(c[0], c[1], c[2], aa).endVertex();
        buf.vertex(m, (float) (b.x + side.x), (float) (b.y + side.y), (float) (b.z + side.z)).color(c[0], c[1], c[2], ab).endVertex();
        buf.vertex(m, (float) (b.x - side.x), (float) (b.y - side.y), (float) (b.z - side.z)).color(c[0], c[1], c[2], ab).endVertex();
    }

    /** A small camera-facing glow (a diamond of two segments). */
    private static void glow(BufferBuilder buf, Matrix4f m, Vector3d p, double r, float[] c, float a) {
        if (a <= 0.01f) return;
        Vector3d side = p.cross(new Vector3d(0, 1, 0));
        if (side.lengthSqr() < 1e-6) side = new Vector3d(1, 0, 0);
        side = side.normalize().scale(r);
        segment(buf, m, p.subtract(side), p.add(side), r * 0.6, c, a, a);
        Vector3d up = side.cross(p).normalize().scale(r);
        segment(buf, m, p.subtract(up), p.add(up), r * 0.6, c, a, a);
    }

    // ---- HUD -----------------------------------------------------------------------------------------------------

    /** Lines drawn in the last frame (tests). */
    public static volatile int hudLines;

    @SubscribeEvent
    public static void onOverlay(RenderGameOverlayEvent.Post event) {
        hudLines = 0;
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || !ClientConfig.THREAD_HUD.get()) return;
        ThreadPacket s = state;
        boolean moon = threadMoon(mc);
        if (!moon && s.reduction <= 0.001f) return;
        MatrixStack ms = event.getMatrixStack();
        FontRenderer font = mc.font;
        int w = event.getWindow().getGuiScaledWidth(), h = event.getWindow().getGuiScaledHeight();
        float scale = ClientConfig.THREAD_HUD_SCALE.get().floatValue();
        int age = (int) (ticks - stateAt);
        List<Object[]> lines = new ArrayList<>(); // text, colour, icon colour
        if (s.reduction > 0.001f) {
            int n = linkedMates(mc.player.getId());
            lines.add(new Object[]{"Thread bond -" + Math.round(s.reduction * 100) + "%" + (n > 0 ? " · " + n + " linked" : ""), 0xF2A0BE, 0xFFF2A0BE});
        }
        if (!s.guardTarget.isEmpty()) {
            lines.add(new Object[]{"Guarding " + s.guardTarget + (s.guardActive ? s.guardEstablished ? " (ready)" : " (active)" : " (out of range)"),
                    s.guardActive ? 0xE0C8F0 : 0x9A8AA6, s.guardActive ? 0xFFE0C8F0 : 0xFF6A5A76});
        }
        if (!s.guardedBy.isEmpty()) lines.add(new Object[]{"Guarded by " + s.guardedBy, 0xE0C8F0, 0xFFE0C8F0});
        int gcd = s.guardCooldown - age;
        if (gcd > 0) lines.add(new Object[]{"Guard recovers " + secs(gcd), 0x9A8AA6, 0xFF6A5A76});
        if (s.lifelineTarget >= 0) lines.add(new Object[]{"Lifeline: " + hearts(s.lifelineMoved) + " given", 0xFFA6C0, 0xFFFF7A9C});
        int lcd = s.lifelineCooldown - age;
        if (lcd > 0) lines.add(new Object[]{"Lifeline " + secs(lcd), 0xB08A96, 0xFF8A6A76});
        int mk = s.markTicks - age;
        if (s.markTarget >= 0 && mk > 0) lines.add(new Object[]{"Knot: hit a second enemy " + secs(mk), 0xFF9ACB, 0xFFFF6FB0});
        int kcd = s.knotCooldown - age;
        if (kcd > 0 && !(s.markTarget >= 0 && mk > 0)) lines.add(new Object[]{"Knotbind " + secs(kcd), 0xB08A96, 0xFF8A6A76});
        if (s.taggedTicks - age > 0) lines.add(new Object[]{"Tagged by yarn!", 0xFF9ACB, 0xFFFF6FB0});
        if (s.lastStitch == 1 && !s.guardTarget.isEmpty()) lines.add(new Object[]{"Last Stitch ready", 0xFFD9A0, 0xFFFFC870});
        if (s.lastStitch == 2) lines.add(new Object[]{"Last Stitch spent", 0x9A8A80, 0xFF7A6A60});
        int widest = 0;
        for (Object[] l : lines) widest = Math.max(widest, 6 + font.width((String) l[0]));
        ms.pushPose();
        float x0 = Math.min(w / 2f + 98, w - widest * scale - 4), y0 = h - 14; // never off the screen edge
        if (x0 < w / 2f + 94) y0 = h - 64; // a narrow window: above the hotbar and hearts instead of over them
        ms.translate(x0, y0, 0);
        ms.scale(scale, scale, 1);
        int y = 0;
        for (Object[] l : lines) {
            AbstractGui.fill(ms, 0, y + 2, 4, y + 6, (int) l[2]);
            font.drawShadow(ms, (String) l[0], 6, y, (int) l[1]);
            y -= 10;
            hudLines++;
        }
        ms.popPose();
        // near the crosshair: Lifeline aim/channel and the Unravel prompt
        int cx = w / 2, cy = h / 2;
        if (s.lifelineTarget >= 0) {
            float f = s.lifelineAim < 1f ? s.lifelineAim : Math.min(1f, s.lifelineMoved / (float) Math.max(0.5, Settings.client().d("thread_lifeline.maxPerActivation")));
            bar(ms, cx, cy + 10, f, s.lifelineAim < 1f ? 0xFFFFB6C8 : 0xFFFF7A9C);
            hudLines++;
        }
        if (s.unravel >= 0f) {
            String key = AbilityClient.KEY.getTranslatedKeyMessage().getString();
            String txt = "Knotted " + secs(Math.max(0, s.knotTicks - age)) + " - stand still, hold [" + key + "] to unravel";
            font.drawShadow(ms, txt, cx - font.width(txt) / 2f, cy + 18, 0xFF9ACB);
            bar(ms, cx, cy + 12, s.unravel, 0xFFFF6FB0);
            hudLines++;
        }
        // other victims' unravel progress on knots you're in (both victims see it)
        for (int i = 0; i + 5 < knots.length; i += 6) {
            boolean mine = knots[i + 1] == mc.player.getId() || knots[i + 2] == mc.player.getId();
            if (!mine) continue;
            int other = knots[i + 1] == mc.player.getId() ? knots[i + 5] : knots[i + 4];
            if (other > 0) {
                String txt = "Partner unravelling " + other + "%";
                font.drawShadow(ms, txt, cx - font.width(txt) / 2f, cy + 28, 0xE0B0C8);
            }
        }
    }

    private static int linkedMates(int me) {
        int n = 0;
        for (int i = 0; i + 1 < links.length; i += 2) if (links[i] == me || links[i + 1] == me) n++;
        return n;
    }

    private static String secs(int ticks) {
        return String.format("%.1fs", Math.max(0, ticks) / 20f);
    }

    private static String hearts(float hp) {
        float hrt = Math.round(hp) / 2f;
        return (hrt == (int) hrt ? String.valueOf((int) hrt) : String.valueOf(hrt)) + (hrt == 1f ? " heart" : " hearts");
    }

    private static void bar(MatrixStack ms, int cx, int y, float f, int col) {
        AbstractGui.fill(ms, cx - 12, y, cx + 12, y + 2, 0x66000000);
        AbstractGui.fill(ms, cx - 12, y, cx - 12 + Math.round(24 * Math.max(0f, Math.min(1f, f))), y + 2, col);
    }
}
