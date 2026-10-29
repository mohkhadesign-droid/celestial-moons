package net.ecmoons.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.ecmoons.config.ClientConfig;
import net.ecmoons.config.Settings;
import net.ecmoons.net.InspectAimPacket;
import net.ecmoons.net.InspectPacket;
import net.ecmoons.net.Network;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.AbstractClientPlayerEntity;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Effect;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;

/**
 * Revealing Moon screen-space parts on the client: the amber screen edges while you are revealed, the Read Your
 * Enemy panel, the aim proposal sent to the server, and Binding Gaze's movement hold.
 * <p>
 * Binding Gaze only clears the movement keys' effect after vanilla read them (walk, strafe, jump; sprinting stops
 * because there is no forward input). The camera, attacking, blocking and item use are untouched, and velocity is
 * never changed, so falling and knockback behave normally.
 */
public final class RevealHud {
    private static InspectPacket panel;
    private static long panelAt = -1000;
    private static int aimTarget = -1, sentTarget = -1;
    private static long ticks;
    /** Panel frames drawn (client test). */
    public static volatile int panelDrawn;
    /** Edge overlay frames drawn (client test). */
    public static volatile int edgesDrawn;

    private RevealHud() {}

    static void reset() {
        panel = null;
        aimTarget = -1;
        sentTarget = -1;
    }

    static void hidePanel() {
        panel = null;
    }

    public static void onInspect(InspectPacket p) {
        if (p.targetId < 0) {
            panel = null;
            return;
        }
        panel = p;
        panelAt = ticks;
    }

    /** The panel is shown only while you are revealed, still aiming at that player and updates keep coming. */
    public static boolean panelVisible() {
        if (testPanel != null) return true;
        return panel != null && RevealClient.revealed() && panel.targetId == aimTarget && ticks - panelAt <= 12;
    }

    /** Client test only: show this panel regardless of aim (a single-player test has nobody else to aim at). */
    private static InspectPacket testPanel;

    public static void testPanel(InspectPacket p) {
        testPanel = p;
    }

    public static InspectPacket panel() {
        return panel;
    }

    public static int aimTarget() {
        return aimTarget;
    }

    // ---- client tick: aim proposal --------------------------------------------------------------------------------

    static void tick(Minecraft mc) {
        ticks++;
        int target = -1;
        if (mc.player != null && mc.level != null && RevealClient.revealed() && Settings.client().b("revealing_inspect.enabled")) {
            target = aimedPlayer(mc, Settings.client().d("revealing_inspect.range"));
        }
        aimTarget = target;
        if (panel != null && (panel.targetId != target || !RevealClient.revealed())) panel = null; // hide at once
        if (target != sentTarget && (ticks % 2 == 0 || target < 0)) {
            sentTarget = target;
            Network.CHANNEL.sendToServer(new InspectAimPacket(target));
        }
    }

    /** The other player under your aim (eye ray, nearest hit within range, not behind blocks), or -1. */
    static int aimedPlayer(Minecraft mc, double range) {
        Vector3d eye = mc.player.getEyePosition(1f);
        Vector3d end = eye.add(mc.player.getViewVector(1f).scale(range));
        int best = -1;
        double bestD = Double.MAX_VALUE;
        Vector3d bestHit = null;
        for (AbstractClientPlayerEntity other : mc.level.players()) {
            if (other == mc.player || !other.isAlive() || other.isSpectator()) continue;
            if (other.distanceToSqr(mc.player) > (range + 2) * (range + 2)) continue;
            AxisAlignedBB box = other.getBoundingBox().inflate(0.1);
            Optional<Vector3d> hit = box.clip(eye, end);
            if (!hit.isPresent()) continue;
            double d = hit.get().distanceToSqr(eye);
            if (d < bestD) {
                bestD = d;
                best = other.getId();
                bestHit = hit.get();
            }
        }
        if (best < 0) return -1;
        RayTraceResult block = mc.level.clip(new RayTraceContext(eye, bestHit, RayTraceContext.BlockMode.COLLIDER,
                RayTraceContext.FluidMode.NONE, mc.player));
        return block.getType() == RayTraceResult.Type.MISS ? best : -1;
    }

    // ---- Binding Gaze: hold voluntary movement -------------------------------------------------------------------

    @SubscribeEvent
    public static void onInput(InputUpdateEvent event) {
        if (!RevealClient.bound() || event.getPlayer() != Minecraft.getInstance().player) return;
        net.minecraft.util.MovementInput in = event.getMovementInput();
        in.forwardImpulse = 0f;
        in.leftImpulse = 0f;
        in.up = in.down = in.left = in.right = false;
        in.jumping = false;
        if (event.getPlayer().isSprinting()) event.getPlayer().setSprinting(false);
    }

    // ---- amber edges (drawn before the HUD, so the HUD itself is never tinted) ------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        float k = RevealClient.revealIntensity(event.getPartialTicks()) * ClientConfig.REVEAL_AMBER_EDGES.get().floatValue();
        if (k <= 0.004f) return;
        int w = event.getWindow().getGuiScaledWidth(), h = event.getWindow().getGuiScaledHeight();
        float e = Math.max(8f, Math.min(w, h) * 0.09f);
        float a = 0.28f * k;
        float r = 1f, g = 0.66f, b = 0.2f;
        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.disableAlphaTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.shadeModel(7425);
        RenderSystem.disableDepthTest();
        Matrix4f m = event.getMatrixStack().last().pose();
        BufferBuilder buf = Tessellator.getInstance().getBuilder();
        buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
        // top, bottom, left, right: full colour at the edge, transparent towards the centre
        quad(buf, m, 0, 0, w, 0, w, e, 0, e, a, a, 0, 0, r, g, b);
        quad(buf, m, 0, h - e, w, h - e, w, h, 0, h, 0, 0, a, a, r, g, b);
        quad(buf, m, 0, 0, e, 0, e, h, 0, h, a, 0, 0, a, r, g, b);
        quad(buf, m, w - e, 0, w, 0, w, h, w - e, h, 0, a, a, 0, r, g, b);
        Tessellator.getInstance().end();
        RenderSystem.shadeModel(7424);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.enableAlphaTest();
        RenderSystem.enableTexture();
        edgesDrawn++;
    }

    private static void quad(BufferBuilder buf, Matrix4f m, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3,
                             float a0, float a1, float a2, float a3, float r, float g, float b) {
        buf.vertex(m, x0, y0, -90f).color(r, g, b, a0).endVertex();
        buf.vertex(m, x3, y3, -90f).color(r, g, b, a3).endVertex();
        buf.vertex(m, x2, y2, -90f).color(r, g, b, a2).endVertex();
        buf.vertex(m, x1, y1, -90f).color(r, g, b, a1).endVertex();
    }

    // ---- Read Your Enemy panel -------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL || !panelVisible()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        MatrixStack ms = event.getMatrixStack();
        FontRenderer font = mc.font;
        InspectPacket p = testPanel != null ? testPanel : panel;
        int w = event.getWindow().getGuiScaledWidth(), h = event.getWindow().getGuiScaledHeight();

        float hearts = p.health / 2f, maxHearts = Math.max(0.5f, p.maxHealth / 2f), absorb = p.absorption / 2f;
        boolean icons = maxHearts <= 10f;
        String healthText = fmt(hearts) + " / " + fmt(maxHearts);
        int lines = 2 + p.effects.size() + (p.more > 0 ? 1 : 0);
        int width = font.width(p.name);
        int heartRow = icons ? (int) Math.ceil(maxHearts) * 8 + 1 : 12 + font.width(healthText);
        if (absorb > 0f) heartRow += 14 + font.width("+" + fmt(absorb));
        width = Math.max(width, heartRow);
        String[] effectLines = new String[p.effects.size()];
        for (int i = 0; i < p.effects.size(); i++) {
            effectLines[i] = effectLine(p.effects.get(i));
            width = Math.max(width, font.width(effectLines[i]));
        }
        int height = lines * 10 - 1;
        // to the right of and just below the crosshair: never over the target itself
        int x = Math.min(w / 2 + 14, w - width - 6), y = Math.min(h / 2 + 8, h - height - 30);
        AbstractGui.fill(ms, x - 4, y - 4, x + width + 4, y + height + 3, 0xE8100c08);
        AbstractGui.fill(ms, x - 4, y - 4, x + width + 4, y - 3, 0xC0d9ab54);
        AbstractGui.fill(ms, x - 4, y + height + 2, x + width + 4, y + height + 3, 0x80d9ab54);
        font.drawShadow(ms, p.name, x, y, 0xFFF4E2B8);
        int hy = y + 10;
        RenderSystem.enableBlend();
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        mc.getTextureManager().bind(AbstractGui.GUI_ICONS_LOCATION);
        int cx = x;
        if (icons) {
            int n = (int) Math.ceil(maxHearts);
            for (int i = 0; i < n; i++) {
                mc.gui.blit(ms, cx + i * 8, hy, 16, 0, 9, 9); // container
                float left = hearts - i;
                if (left >= 1f) mc.gui.blit(ms, cx + i * 8, hy, 52, 0, 9, 9);
                else if (left >= 0.25f) mc.gui.blit(ms, cx + i * 8, hy, 61, 0, 9, 9);
            }
            cx += n * 8 + 1;
        } else {
            mc.gui.blit(ms, cx, hy, 16, 0, 9, 9);
            mc.gui.blit(ms, cx, hy, 52, 0, 9, 9);
            font.drawShadow(ms, healthText, cx + 12, hy + 1, 0xFFFF6A6A);
            mc.getTextureManager().bind(AbstractGui.GUI_ICONS_LOCATION);
            cx += 12 + font.width(healthText);
        }
        if (absorb > 0f) {
            cx += 5;
            mc.gui.blit(ms, cx, hy, 16, 0, 9, 9);
            mc.gui.blit(ms, cx, hy, 160, 0, 9, 9); // absorption (golden) heart, shown apart from health
            font.drawShadow(ms, "+" + fmt(absorb), cx + 11, hy + 1, 0xFFFFD25A);
        }
        int ey = hy + 10;
        for (String line : effectLines) {
            font.drawShadow(ms, line, x, ey, 0xFFE8DCC2);
            ey += 10;
        }
        if (p.more > 0) font.drawShadow(ms, "+" + p.more + " more", x, ey, 0xFFA09888);
        panelDrawn++;
    }

    static String effectLine(InspectPacket.Effect e) {
        Effect effect = ForgeRegistries.POTIONS.getValue(e.id);
        String name = effect != null ? effect.getDisplayName().getString() : e.id.getPath();
        if (name.length() > 18) name = name.substring(0, 17) + "…";
        if (e.amplifier >= 1 && e.amplifier <= 9) name += " " + I18n.get("enchantment.level." + (e.amplifier + 1)); // as vanilla's effect list
        else if (e.amplifier > 9) name += " " + (e.amplifier + 1);
        return name + "  " + duration(e.duration);
    }

    static String duration(int ticks) {
        if (ticks >= 20 * 60 * 100) return "**:**";
        int s = ticks / 20;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    static String fmt(float v) {
        float r = Math.round(v * 2f) / 2f;
        return r == (int) r ? String.valueOf((int) r) : String.valueOf(r);
    }
}
