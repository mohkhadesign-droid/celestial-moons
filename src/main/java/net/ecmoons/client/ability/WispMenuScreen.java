package net.ecmoons.client.ability;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.ecmoons.feature.ability.WispAbility;
import net.ecmoons.net.WispMenuPacket;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.StringTextComponent;

/**
 * The small Wisp menu opened by the Moon Ability key: Pilot Wisp, View observer n, Dismiss. Only actions that are
 * available right now are offered; observers that are too far from the body are listed as out of range.
 * Number keys 1-n view an observer; the Moon Ability key or Escape closes it.
 */
public class WispMenuScreen extends Screen {
    public final WispMenuPacket data;
    private boolean dismissing;
    public static WispMenuScreen lastOpened; // tests

    public WispMenuScreen(WispMenuPacket data) {
        super(new StringTextComponent("Wisp Moon"));
        this.data = data;
        lastOpened = this;
    }

    @Override
    protected void init() {
        buttons.clear();
        children.clear();
        int cx = width / 2, y = height / 2 - 52, bw = 150;
        if (!dismissing) {
            if (data.cooldown <= 0) {
                addButton(new Button(cx - bw / 2, y, bw, 20, new StringTextComponent("Pilot Wisp"), b -> act(WispAbility.PILOT, 0)));
                y += 24;
            } else {
                y += 14;
            }
            for (int i = 0; i < data.numbers.length; i++) {
                final int n = data.numbers[i];
                String label = data.inRange[i] ? String.format("View observer %d (%.0f m)", n, data.distances[i])
                        : String.format("Observer %d: out of range (%.0f m)", n, data.distances[i]);
                Button b = addButton(new Button(cx - bw / 2, y, bw, 20, new StringTextComponent(label), x -> act(WispAbility.VIEW, n)));
                b.active = data.inRange[i];
                y += 22;
            }
            if (data.numbers.length > 0) {
                addButton(new Button(cx - bw / 2, y + 2, bw, 20, new StringTextComponent("Dismiss observers..."), b -> {
                    dismissing = true;
                    init();
                }));
            }
        } else {
            for (int i = 0; i < data.numbers.length; i++) {
                final int n = data.numbers[i];
                addButton(new Button(cx - bw / 2, y, bw, 20, new StringTextComponent("Dismiss observer " + n), b -> act(WispAbility.DISMISS, n)));
                y += 22;
            }
            addButton(new Button(cx - bw / 2, y, bw, 20, new StringTextComponent("Dismiss all"), b -> act(WispAbility.DISMISS_ALL, 0)));
            y += 22;
            addButton(new Button(cx - bw / 2, y, bw, 20, new StringTextComponent("Back"), b -> {
                dismissing = false;
                init();
            }));
        }
    }

    private void act(byte action, int index) {
        WispClient.send(action, index);
        onClose();
    }

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float pt) {
        int cx = width / 2, top = height / 2 - 70;
        fill(ms, cx - 84, top - 4, cx + 84, top + 12 + 24 + 22 * Math.max(1, data.numbers.length) + 30, 0x90101A26);
        drawCenteredString(ms, font, "Wisp Moon", cx, top + 2, 0x9FE6FF);
        if (!dismissing && data.cooldown > 0) {
            drawCenteredString(ms, font, "Wisp ready in " + (data.cooldown + 19) / 20 + "s", cx, height / 2 - 48, 0x8FA8B8);
        }
        if (!dismissing && data.numbers.length == 0) {
            drawCenteredString(ms, font, "No observers (place them while piloting)", cx, height / 2 - 52 + (data.cooldown <= 0 ? 26 : 14), 0x8FA8B8);
        }
        super.render(ms, mouseX, mouseY, pt);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (AbilityClient.KEY.matches(key, scan)) {
            onClose();
            return true;
        }
        if (!dismissing && key >= org.lwjgl.glfw.GLFW.GLFW_KEY_1 && key <= org.lwjgl.glfw.GLFW.GLFW_KEY_9) {
            int n = key - org.lwjgl.glfw.GLFW.GLFW_KEY_1 + 1;
            for (int i = 0; i < data.numbers.length; i++) {
                if (data.numbers[i] == n && data.inRange[i]) {
                    act(WispAbility.VIEW, n);
                    return true;
                }
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
