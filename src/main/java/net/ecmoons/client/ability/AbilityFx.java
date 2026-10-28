package net.ecmoons.client.ability;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.ecmoons.net.AbilityFxPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.AbstractClientPlayerEntity;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.model.PlayerModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Client drawings for the abilities. Nothing here is an entity: afterimages are a translucent copy of the player's
 * model drawn for a few ticks (they cannot be hit, block anything or make sounds), and shells are a translucent
 * sphere drawn where the server says one is.
 */
public final class AbilityFx {
    private static final class Ghost {
        int entityId, life, age, stage;
        double x, y, z;
        float yaw;
    }

    private static final class ShellView {
        double x, y, z;
        float r, frac, flash;
        int left, removing = -1;
    }

    private static final List<Ghost> GHOSTS = new ArrayList<>();
    private static final Map<Integer, ShellView> SHELLS = new HashMap<>();
    private static PlayerModel<AbstractClientPlayerEntity> wide, slim;
    private static ClientWorld lastWorld;
    public static int lastGhostsDrawn, lastShellsDrawn;

    private AbilityFx() {}

    public static void onPacket(AbilityFxPacket p) {
        switch (p.kind) {
            case AbilityFxPacket.GHOST: {
                Ghost g = new Ghost();
                g.entityId = p.id;
                g.x = p.x;
                g.y = p.y;
                g.z = p.z;
                g.yaw = p.yaw;
                g.life = Math.max(2, p.t);
                g.stage = (int) p.f;
                if (GHOSTS.size() < 24) GHOSTS.add(g);
                break;
            }
            case AbilityFxPacket.SHELL_ADD: {
                ShellView s = SHELLS.computeIfAbsent(p.id, k -> new ShellView());
                s.x = p.x;
                s.y = p.y;
                s.z = p.z;
                s.r = p.r;
                s.left = p.t;
                s.frac = p.f;
                s.removing = -1;
                break;
            }
            case AbilityFxPacket.SHELL_HIT: {
                ShellView s = SHELLS.get(p.id);
                if (s != null) {
                    s.flash = 1f;
                    s.frac = p.f;
                }
                break;
            }
            case AbilityFxPacket.SHELL_REMOVE: {
                ShellView s = SHELLS.get(p.id);
                if (s != null && s.removing < 0) s.removing = 6;
                break;
            }
            default:
        }
    }

    public static int shellCount() {
        return SHELLS.size();
    }

    public static int ghostCount() {
        return GHOSTS.size();
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastWorld) {
            lastWorld = mc.level;
            GHOSTS.clear();
            SHELLS.clear();
        }
        if (mc.isPaused()) return;
        GHOSTS.removeIf(g -> ++g.age >= g.life);
        for (Iterator<ShellView> it = SHELLS.values().iterator(); it.hasNext(); ) {
            ShellView s = it.next();
            s.flash = Math.max(0f, s.flash - 0.15f);
            if (s.removing >= 0 && --s.removing < 0) it.remove();
            else if (--s.left < -40) it.remove(); // safety: never stays without server refreshes
        }
    }

    @SubscribeEvent
    public static void onRender(RenderWorldLastEvent event) {
        lastGhostsDrawn = lastShellsDrawn = 0;
        if (GHOSTS.isEmpty() && SHELLS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        MatrixStack ms = event.getMatrixStack();
        float pt = event.getPartialTicks();
        Vector3d cam = mc.gameRenderer.getMainCamera().getPosition();
        if (!GHOSTS.isEmpty()) renderGhosts(mc, ms, cam, pt);
        if (!SHELLS.isEmpty()) renderShells(mc, ms, cam, pt);
    }

    private static void renderGhosts(Minecraft mc, MatrixStack ms, Vector3d cam, float pt) {
        if (wide == null) {
            wide = new PlayerModel<>(0f, false);
            slim = new PlayerModel<>(0f, true);
        }
        IRenderTypeBuffer.Impl buffers = mc.renderBuffers().bufferSource();
        for (Ghost g : GHOSTS) {
            Entity e = mc.level.getEntity(g.entityId);
            ResourceLocation skin = DefaultPlayerSkin.getDefaultSkin();
            PlayerModel<AbstractClientPlayerEntity> model = wide;
            if (e instanceof AbstractClientPlayerEntity) {
                AbstractClientPlayerEntity pl = (AbstractClientPlayerEntity) e;
                skin = pl.getSkinTextureLocation();
                if ("slim".equals(pl.getModelName())) model = slim;
            }
            float life = (g.age + pt) / g.life;
            float alpha = (0.55f - 0.08f * (3 - g.stage)) * Math.max(0f, 1f - life);
            if (alpha <= 0.01f) continue;
            ms.pushPose();
            ms.translate(g.x - cam.x, g.y - cam.y, g.z - cam.z);
            ms.mulPose(Vector3f.YP.rotationDegrees(180f - g.yaw));
            ms.scale(-0.9375f, -0.9375f, 0.9375f);
            ms.translate(0, -1.501, 0);
            model.young = false;
            model.crouching = false;
            model.riding = false;
            model.attackTime = 0;
            RenderType type = RenderType.entityTranslucent(skin);
            model.renderToBuffer(ms, buffers.getBuffer(type), 0xF000F0, OverlayTexture.NO_OVERLAY, 0.78f, 0.72f, 1.0f, alpha);
            buffers.endBatch(type);
            ms.popPose();
            lastGhostsDrawn++;
        }
    }

    private static void renderShells(Minecraft mc, MatrixStack ms, Vector3d cam, float pt) {
        mc.gameRenderer.lightTexture().turnOffLightLayer();
        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        Matrix4f m = ms.last().pose();
        int lat = 10, lon = 20;
        for (ShellView s : SHELLS.values()) {
            float fade = s.removing >= 0 ? s.removing / 6f : 1f;
            float a = (0.09f + 0.22f * s.flash) * fade;
            float rr = 0.72f + 0.25f * s.flash, gg = 0.93f, bb = 0.82f;
            float cx = (float) (s.x - cam.x), cy = (float) (s.y - cam.y), cz = (float) (s.z - cam.z);
            BufferBuilder buf = Tessellator.getInstance().getBuilder();
            buf.begin(7, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < lat; i++) {
                double t0 = Math.PI * i / lat - Math.PI / 2, t1 = Math.PI * (i + 1) / lat - Math.PI / 2;
                for (int j = 0; j < lon; j++) {
                    double p0 = 2 * Math.PI * j / lon, p1 = 2 * Math.PI * (j + 1) / lon;
                    vert(buf, m, cx, cy, cz, s.r, t0, p0, rr, gg, bb, a);
                    vert(buf, m, cx, cy, cz, s.r, t1, p0, rr, gg, bb, a);
                    vert(buf, m, cx, cy, cz, s.r, t1, p1, rr, gg, bb, a);
                    vert(buf, m, cx, cy, cz, s.r, t0, p1, rr, gg, bb, a);
                }
            }
            Tessellator.getInstance().end();
            // brighter plate seams so the edge is easy to read, coloured by remaining durability
            buf.begin(1, DefaultVertexFormats.POSITION_COLOR);
            float la = (0.35f + 0.4f * s.flash) * fade;
            float lr = 0.75f + 0.25f * (1 - s.frac), lg = 0.95f * (0.5f + 0.5f * s.frac);
            for (int i = 1; i < lat; i += 2) {
                double t = Math.PI * i / lat - Math.PI / 2;
                for (int j = 0; j < lon; j++) {
                    vert(buf, m, cx, cy, cz, s.r, t, 2 * Math.PI * j / lon, lr, lg, bb, la);
                    vert(buf, m, cx, cy, cz, s.r, t, 2 * Math.PI * (j + 1) / lon, lr, lg, bb, la);
                }
            }
            for (int j = 0; j < lon; j += 4) {
                for (int i = 0; i < lat; i++) {
                    vert(buf, m, cx, cy, cz, s.r, Math.PI * i / lat - Math.PI / 2, 2 * Math.PI * j / lon, lr, lg, bb, la);
                    vert(buf, m, cx, cy, cz, s.r, Math.PI * (i + 1) / lat - Math.PI / 2, 2 * Math.PI * j / lon, lr, lg, bb, la);
                }
            }
            Tessellator.getInstance().end();
            lastShellsDrawn++;
        }
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableTexture();
        RenderSystem.disableBlend();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
    }

    private static void vert(BufferBuilder buf, Matrix4f m, float cx, float cy, float cz, float r, double theta, double phi,
                             float red, float green, float blue, float a) {
        float x = (float) (Math.cos(theta) * Math.cos(phi)) * r, y = (float) Math.sin(theta) * r, z = (float) (Math.cos(theta) * Math.sin(phi)) * r;
        buf.vertex(m, cx + x, cy + y, cz + z).color(red, green, blue, a).endVertex();
    }
}
