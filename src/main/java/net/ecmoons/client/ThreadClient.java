package net.ecmoons.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.particles.RedstoneParticleData;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Thread Moon on the client: rose thread particles between bonded teammates (only members of the team receive the
 * links, so nobody else sees them) and a small "Thread bond" indicator above the hotbar.
 */
public final class ThreadClient {
    private static final RedstoneParticleData ROSE = new RedstoneParticleData(0.95f, 0.45f, 0.62f, 0.6f);
    private static float reduction;
    private static int[] links = new int[0];
    private static ClientWorld lastWorld;
    private static int tick;

    private ThreadClient() {}

    public static void update(float r, int[] l) {
        reduction = r;
        links = l;
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
        }
        if (mc.level == null || mc.isPaused() || links.length == 0) return;
        if (++tick % 3 != 0) return;
        for (int i = 0; i + 1 < links.length; i += 2) {
            Entity a = mc.level.getEntity(links[i]), b = mc.level.getEntity(links[i + 1]);
            if (a == null || b == null) continue;
            Vector3d from = a.position().add(0, a.getBbHeight() * 0.55, 0);
            Vector3d to = b.position().add(0, b.getBbHeight() * 0.55, 0);
            double len = from.distanceTo(to);
            int n = Math.max(2, (int) (len / 0.9));
            double flow = (tick / 3 % 4) / 4.0;
            for (int k = 0; k < n; k++) {
                double t = (k + flow) / n;
                Vector3d p = from.add(to.subtract(from).scale(t));
                double sag = Math.sin(Math.PI * t) * Math.min(0.6, len * 0.04);
                mc.level.addParticle(ROSE, p.x, p.y - sag, p.z, 0, 0, 0);
            }
        }
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
