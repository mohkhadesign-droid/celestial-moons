package net.ecmoons.client.ability;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.ability.WispAbility;
import net.ecmoons.feature.ability.wisp.ObserverEntity;
import net.ecmoons.feature.ability.wisp.WispEntity;
import net.ecmoons.net.Network;
import net.ecmoons.net.WispActionPacket;
import net.ecmoons.net.WispInputPacket;
import net.ecmoons.net.WispMenuPacket;
import net.ecmoons.net.WispStatePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.gui.screen.IngameMenuScreen;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.util.InputMappings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Wisp Moon on the client: switches the camera to the wisp or an observer when the server says so, turns the
 * movement keys into wisp input (the body never walks), blocks attacking/using/interacting while away from the
 * body, predicts the wisp's movement for a smooth camera, and draws the player's own body (vanilla skips the local
 * player whenever the camera is another entity). The game mode is never touched.
 */
public final class WispClient {
    public static byte mode = WispStatePacket.NONE;
    public static int entityId = -1, number, count, max = 3, cooldown;
    private static float baseYaw, basePitch;
    private static float inForward, inStrafe, inUp;
    private static int waiting;
    private static long ticks, stateAt;
    public static int lastBodyDrawn;
    /** Tests: movement input to use instead of the keyboard (forward, strafe, up), or null. */
    public static float[] testInput;

    private WispClient() {}

    public static void onState(WispStatePacket p) {
        mode = p.mode;
        entityId = p.entityId;
        baseYaw = p.baseYaw;
        basePitch = p.basePitch;
        number = p.number;
        count = p.count;
        max = p.max;
        cooldown = p.cooldown;
        stateAt = ticks;
        waiting = 0;
        WispEntity.localPilotId = mode == WispStatePacket.PILOT ? entityId : -1;
        if (mode == WispStatePacket.NONE) restoreCamera();
        else {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof WispMenuScreen) mc.setScreen(null);
        }
    }

    public static void onMenu(WispMenuPacket p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mode != WispStatePacket.NONE) return;
        mc.setScreen(new WispMenuScreen(p));
    }

    public static boolean away() {
        return mode != WispStatePacket.NONE;
    }

    public static void send(byte action, int index) {
        Network.CHANNEL.sendToServer(new WispActionPacket(action, index));
    }

    private static void restoreCamera() {
        Minecraft mc = Minecraft.getInstance();
        WispEntity.localPilotId = -1;
        if (mc.player != null && mc.getCameraEntity() != mc.player) mc.setCameraEntity(mc.player);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            mode = WispStatePacket.NONE;
            WispEntity.localPilotId = -1;
            return;
        }
        if (mode == WispStatePacket.NONE) {
            Entity cam = mc.getCameraEntity();
            if (cam instanceof WispEntity || cam instanceof ObserverEntity) restoreCamera(); // never left behind
            return;
        }
        Entity e = mc.level.getEntity(entityId);
        if (e == null || e.removed) {
            restoreCamera();
            if (++waiting > 60) { // the entity never arrived (or vanished): ask the server to end it cleanly
                send(WispAbility.EXIT, 0);
                waiting = 0;
            }
            return;
        }
        waiting = 0;
        if (mc.getCameraEntity() != e) mc.setCameraEntity(e);
        ClientPlayerEntity p = mc.player;
        if (mode == WispStatePacket.PILOT && e instanceof WispEntity) {
            float f = inForward, s = inStrafe, u = inUp;
            if (testInput != null) {
                f = testInput[0];
                s = testInput[1];
                u = testInput[2];
            }
            Network.CHANNEL.sendToServer(new WispInputPacket(f, s, u, p.yRot, p.xRot));
            // predict the same movement the server will make (corrections under 1.5 blocks are ignored)
            Settings st = Settings.client();
            Vector3d delta = WispEntity.motion(f, s, u, p.yRot, st.d("wisp.pilotSpeed"));
            Vector3d center = e.position().add(0, e.getBbHeight() / 2, 0);
            delta = WispEntity.clampToRange(center, delta, new Vector3d(p.getX(), p.getEyeY(), p.getZ()), st.d("wisp.pilotRange"));
            if (delta.lengthSqr() > 1e-8 && mc.level.hasChunkAt(new BlockPos(center.add(delta)))) e.move(MoverType.SELF, delta);
            e.setDeltaMovement(Vector3d.ZERO);
            e.yRot = p.yRot;
            e.xRot = p.xRot;
        } else if (mode == WispStatePacket.VIEW) {
            clampView(p);
        }
    }

    /** While viewing, the look can only turn so far from the observer's direction. */
    private static void clampView(ClientPlayerEntity p) {
        Settings st = Settings.client();
        float yr = (float) st.d("wisp.viewYawRange"), pr = (float) st.d("wisp.viewPitchRange");
        float dy = MathHelper.clamp(MathHelper.wrapDegrees(p.yRot - baseYaw), -yr, yr);
        p.yRot = baseYaw + dy;
        p.yRotO = p.yRot;
        p.xRot = MathHelper.clamp(p.xRot, basePitch - pr, basePitch + pr);
        p.xRotO = p.xRot;
    }

    @SubscribeEvent
    public static void onCamera(EntityViewRenderEvent.CameraSetup event) {
        if (mode == WispStatePacket.NONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getCameraEntity() == mc.player) return;
        float pt = (float) event.getRenderPartialTicks();
        float yaw = mc.player.getViewYRot(pt), pitch = mc.player.getViewXRot(pt);
        if (mode == WispStatePacket.VIEW) {
            Settings st = Settings.client();
            float yr = (float) st.d("wisp.viewYawRange"), pr = (float) st.d("wisp.viewPitchRange");
            yaw = baseYaw + MathHelper.clamp(MathHelper.wrapDegrees(yaw - baseYaw), -yr, yr);
            pitch = MathHelper.clamp(pitch, basePitch - pr, basePitch + pr);
        }
        event.setYaw(yaw);
        event.setPitch(pitch);
    }

    /** Movement keys steer the wisp; the body itself gets no movement input at all. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onInput(InputUpdateEvent event) {
        if (mode == WispStatePacket.NONE) return;
        net.minecraft.util.MovementInput in = event.getMovementInput();
        inForward = in.forwardImpulse;
        inStrafe = in.leftImpulse;
        inUp = (in.jumping ? 1 : 0) - (in.shiftKeyDown ? 1 : 0);
        in.forwardImpulse = 0;
        in.leftImpulse = 0;
        in.jumping = false;
        in.shiftKeyDown = false;
        in.up = in.down = in.left = in.right = false;
    }

    /** No attacking, using or picking blocks from the body; the Use key places an observer while piloting. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClick(InputEvent.ClickInputEvent event) {
        if (mode == WispStatePacket.NONE) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (mode == WispStatePacket.PILOT && event.isUseItem()) send(WispAbility.PLACE, 0);
    }

    /** Escape returns to the body instead of opening the pause menu. */
    @SubscribeEvent
    public static void onGui(GuiOpenEvent event) {
        if (mode == WispStatePacket.NONE || !(event.getGui() instanceof IngameMenuScreen)) return;
        Minecraft mc = Minecraft.getInstance();
        if (InputMappings.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_ESCAPE)) {
            event.setCanceled(true);
            send(WispAbility.EXIT, 0);
        }
    }

    @SubscribeEvent
    public static void onHand(RenderHandEvent event) {
        if (mode != WispStatePacket.NONE) event.setCanceled(true);
    }

    /** Vanilla never draws the local player while the camera is another entity: draw the body ourselves. */
    @SubscribeEvent
    public static void onRender(RenderWorldLastEvent event) {
        lastBodyDrawn = 0;
        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.getCameraEntity() == p || mc.getCameraEntity() == null) return;
        float pt = event.getPartialTicks();
        Vector3d cam = mc.gameRenderer.getMainCamera().getPosition();
        EntityRendererManager rm = mc.getEntityRenderDispatcher();
        IRenderTypeBuffer.Impl buf = mc.renderBuffers().bufferSource();
        double x = MathHelper.lerp(pt, p.xOld, p.getX()), y = MathHelper.lerp(pt, p.yOld, p.getY()), z = MathHelper.lerp(pt, p.zOld, p.getZ());
        MatrixStack ms = event.getMatrixStack();
        rm.render(p, x - cam.x, y - cam.y, z - cam.z, MathHelper.lerp(pt, p.yRotO, p.yRot), pt, ms, buf, rm.getPackedLightCoords(p, pt));
        buf.endBatch();
        lastBodyDrawn = 1;
    }

    @SubscribeEvent
    public static void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        MatrixStack ms = event.getMatrixStack();
        int w = event.getWindow().getGuiScaledWidth(), h = event.getWindow().getGuiScaledHeight();
        if (mode == WispStatePacket.PILOT) {
            Entity e = mc.level.getEntity(entityId);
            String dist = e == null ? "" : String.format(" · %.0f/%.0f m", e.position().distanceTo(mc.player.position()),
                    Settings.client().d("wisp.pilotRange"));
            mc.font.drawShadow(ms, "Wisp" + dist + " · observers " + count + "/" + max, w / 2f - mc.font.width("Wisp" + dist
                    + " · observers " + count + "/" + max) / 2f, h - 59, 0x9FE6FF);
            String hint = "Use: place observer · Moon Ability or Esc: return";
            mc.font.drawShadow(ms, hint, w / 2f - mc.font.width(hint) / 2f, h - 49, 0xBFDDEA);
        } else if (mode == WispStatePacket.VIEW) {
            String t = "Observer " + number + " · Moon Ability or Esc: return";
            mc.font.drawShadow(ms, t, w / 2f - mc.font.width(t) / 2f, h - 49, 0x9FE6FF);
        } else {
            int left = cooldown - (int) (ticks - stateAt);
            if (left > 0 && net.ecmoons.Moons.is(mc.level, net.ecmoons.MoonType.WISP)) {
                float f = Math.min(1f, left / (float) Math.max(1, Settings.client().i("wisp.pilotCooldownSeconds") * 20));
                int cx = w / 2, cy = h / 2;
                net.minecraft.client.gui.AbstractGui.fill(ms, cx - 11, cy + 18, cx - 11 + Math.round(22 * f), cy + 19, 0x889FE6FF);
            }
        }
    }
}
