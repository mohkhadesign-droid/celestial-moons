package net.ecmoons.client.atmo;

import net.ecmoons.MoonType;
import net.ecmoons.client.RevealClient;
import net.ecmoons.net.AtmoPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Client event wiring for the atmosphere module (registered only on the physical client). */
public final class AtmoEvents {
    private AtmoEvents() {}

    public static void onPacket(byte kind, int a, int b, int c) {
        Minecraft mc = Minecraft.getInstance();
        switch (kind) {
            case AtmoPacket.PREVIEW:
                Atmosphere.setPreview(a < 0 || a >= MoonType.values().length ? null : MoonType.values()[a]);
                break;
            case AtmoPacket.DEMO:
                if (a == AtmoPacket.DEMO_REVEAL) RevealClient.demo(100);
                else if (a == AtmoPacket.DEMO_WAR) AtmoWar.demo(200);
                else if (a == AtmoPacket.DEMO_FEAST && mc.player != null) AtmoParticles.feastBurst(mc.player.getX(), mc.player.getY(), mc.player.getZ(), true);
                break;
            case AtmoPacket.WAR:
                AtmoWar.onPacket(a, b, c);
                break;
            case AtmoPacket.GLUTTONY:
                if (mc.level != null) {
                    Entity e = mc.level.getEntity(a);
                    if (e != null) AtmoParticles.feastBurst(e.getX(), e.getY(), e.getZ(), b == 1);
                }
                break;
            default:
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) Atmosphere.tick();
    }

    @SubscribeEvent
    public static void onFogColor(EntityViewRenderEvent.FogColors event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !event.getInfo().getFluidInCamera().isEmpty()) return;
        float[] c = Atmosphere.fogColor(mc.level, event.getRed(), event.getGreen(), event.getBlue(), (float) event.getRenderPartialTicks());
        if (c == null) return;
        event.setRed(c[0]);
        event.setGreen(c[1]);
        event.setBlue(c[2]);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        AtmoParticles.render(event.getMatrixStack(), event.getPartialTicks());
    }

    @SubscribeEvent
    public static void onSound(PlaySoundEvent event) {
        if (event.getResultSound() != null) event.setResultSound(AtmoSounds.quieten(event.getResultSound()));
    }

    @SubscribeEvent
    public static void onJump(LivingEvent.LivingJumpEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getEntityLiving() == mc.player && Atmosphere.weight(MoonType.DRIFT) > 0f) {
            AtmoParticles.jumpKick(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        }
    }
}
