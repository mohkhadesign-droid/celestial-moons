package net.ecmoons.client.ability;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.net.AbilityPacket;
import net.ecmoons.net.Network;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.glfw.GLFW;

/**
 * The rebindable "Moon Ability" key (default V, Controls -> Enhanced Celestials Moons) and the small indicator next to
 * the crosshair. The client only reports key down/up and the direction the movement keys point; the server decides
 * everything and sends back what to show.
 */
public final class AbilityClient {
    public static final KeyBinding KEY = new KeyBinding("key.ecmoons.moon_ability", KeyConflictContext.IN_GAME,
            InputMappings.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.ecmoons");
    private static boolean wasDown, sentPress, forced;
    // last state from the server
    private static byte kind;
    private static int a, b, c, d;
    private static long receivedAt, ticks;

    private AbilityClient() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY);
    }

    public static void onState(byte k, int pa, int pb, int pc, int pd) {
        kind = k;
        a = pa;
        b = pb;
        c = pc;
        d = pd;
        receivedAt = ticks;
    }

    /** Tests: press/release the key as if the player did. */
    public static void testKey(boolean down) {
        forced = down;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if (mc.player == null || mc.level == null) {
            wasDown = sentPress = false;
            kind = 0;
            return;
        }
        boolean down = (KEY.isDown() && mc.screen == null) || forced;
        if (down && !wasDown) {
            MoonType m = Moons.active(mc.level);
            if (m == MoonType.AFTERIMAGE || m == MoonType.SHELL) {
                float yaw = (float) Math.toRadians(mc.player.yRot);
                float fwd = mc.player.input.forwardImpulse, strafe = mc.player.input.leftImpulse;
                float dx = -(float) Math.sin(yaw) * fwd + (float) Math.cos(yaw) * strafe;
                float dz = (float) Math.cos(yaw) * fwd + (float) Math.sin(yaw) * strafe;
                Network.CHANNEL.sendToServer(new AbilityPacket(AbilityPacket.PRESS, dx, dz));
                sentPress = true;
            } else {
                mc.player.displayClientMessage(new StringTextComponent("No moon ability tonight").withStyle(TextFormatting.GRAY), true);
            }
        } else if (!down && wasDown && sentPress) {
            Network.CHANNEL.sendToServer(new AbilityPacket(AbilityPacket.RELEASE, 0, 0));
            sentPress = false;
        }
        wasDown = down;
    }

    private static int elapsed() {
        return (int) (ticks - receivedAt);
    }

    /** Afterimage: 1 = stepping, 2 = window open, 3 = final bonus; 0 otherwise. */
    public static int afterimagePhase() {
        if (kind != 1) return 0;
        if (b == 2 && elapsed() > c + 2) return 0;
        if (b == 3 && elapsed() > c) return 0;
        return b;
    }

    public static int afterimageStage() {
        return kind == 1 ? a : 0;
    }

    public static int shellState() {
        return kind == 2 ? a : 0;
    }

    @SubscribeEvent
    public static void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL || kind == 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        MatrixStack ms = event.getMatrixStack();
        int cx = event.getWindow().getGuiScaledWidth() / 2, cy = event.getWindow().getGuiScaledHeight() / 2;
        float pt = event.getPartialTicks();
        int e = elapsed();
        if (kind == 1) {
            int phase = afterimagePhase();
            if (phase == 1 || phase == 2) {
                for (int i = 0; i < 3; i++) {
                    int x = cx - 11 + i * 8, y = cy + 9;
                    int col;
                    if (i < a) col = 0xCCB8A8F0;                                       // step done
                    else if (i == a && phase == 2) col = (((ticks / 2) & 1) == 0) ? 0xFFFFFFFF : 0xFFE6DCFF; // press now
                    else col = 0x66FFFFFF;                                             // later
                    AbstractGui.fill(ms, x, y, x + 6, y + 6, col);
                }
            } else if (phase == 3) { // final attack window: highlighted crosshair corners
                int col = 0xE0D2C4FF;
                AbstractGui.fill(ms, cx - 7, cy - 7, cx - 4, cy - 6, col);
                AbstractGui.fill(ms, cx - 7, cy - 7, cx - 6, cy - 4, col);
                AbstractGui.fill(ms, cx + 4, cy - 7, cx + 7, cy - 6, col);
                AbstractGui.fill(ms, cx + 6, cy - 7, cx + 7, cy - 4, col);
                AbstractGui.fill(ms, cx - 7, cy + 6, cx - 4, cy + 7, col);
                AbstractGui.fill(ms, cx - 7, cy + 4, cx - 6, cy + 7, col);
                AbstractGui.fill(ms, cx + 4, cy + 6, cx + 7, cy + 7, col);
                AbstractGui.fill(ms, cx + 6, cy + 4, cx + 7, cy + 7, col);
            }
            cooldownBar(ms, cx, cy, d - e, 0xFFB8A8F0, 8 * 20);
        } else if (kind == 2) {
            if (a == 1) { // charging
                float f = Math.min(1f, e / (float) Math.max(1, c));
                bar(ms, cx, cy + 9, f, 0xFFBFE3CF);
            } else if (a == 2) { // active: time left and durability
                bar(ms, cx, cy + 9, Math.max(0f, c / (float) Math.max(1, d)), 0xFFBFE3CF);
                if (b - e <= 0) kind = 0;
            } else {
                cooldownBar(ms, cx, cy, d - e, 0xFFBFE3CF, 20 * 20);
            }
        }
    }

    private static void bar(MatrixStack ms, int cx, int y, float f, int col) {
        AbstractGui.fill(ms, cx - 11, y, cx + 11, y + 2, 0x66000000);
        AbstractGui.fill(ms, cx - 11, y, cx - 11 + Math.round(22 * f), y + 2, col);
    }

    /** Small cooldown indicator: a thin shrinking bar below the crosshair. */
    private static void cooldownBar(MatrixStack ms, int cx, int cy, int left, int col, int full) {
        if (left <= 0) return;
        float f = Math.min(1f, left / (float) full);
        AbstractGui.fill(ms, cx - 11, cy + 18, cx - 11 + Math.round(22 * f), cy + 19, (col & 0x00FFFFFF) | 0x88000000);
    }
}
