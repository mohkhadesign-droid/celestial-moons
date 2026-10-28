package net.ecmoons.client.atmo;

import net.ecmoons.MoonType;
import net.ecmoons.net.AtmoPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;

/**
 * War Moon pre-battle build-up on the client, driven by the server's real tournament phases (AtmoPacket):
 * during the warning the sky darkens and turbulence and orange cloud flashes increase; when players are summoned
 * there is one brief pulse; afterwards the normal War Moon atmosphere returns. Purely visual.
 */
public final class AtmoWar {
    private static long warnStart = -1, warnEnd = -1;
    private static int summonTicks;

    private AtmoWar() {}

    public static void onPacket(int phase, int ticksLeft, int totalTicks) {
        long now = Atmosphere.ticks();
        switch (phase) {
            case AtmoPacket.WAR_WARNING:
                warnEnd = now + ticksLeft;
                warnStart = warnEnd - Math.max(ticksLeft, totalTicks);
                break;
            case AtmoPacket.WAR_SUMMON:
                warnStart = warnEnd = -1;
                summon();
                break;
            default:
                warnStart = warnEnd = -1;
                break;
        }
    }

    /** One dramatic but brief pulse (also used by the preview demo). */
    public static void summon() {
        summonTicks = 40;
        if (!Atmosphere.on("enableSkyAnimations") || !AtmoProfile.of(MoonType.WAR).flag("preBattleBuildup")) return;
        Atmosphere.pulse(0.8f, 40, new float[]{1f, 0.45f, 0.2f});
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && Atmosphere.on("enableAmbientSounds")) {
            mc.getSoundManager().play(new SimpleSound(SoundEvents.LIGHTNING_BOLT_THUNDER, SoundCategory.AMBIENT,
                    0.35f * AtmoSounds.clientVolume(), 0.55f, mc.player.getX(), mc.player.getY() + 20, mc.player.getZ()));
        }
    }

    private static long demoSummonAt = -1;

    /** Preview: a short warning build-up ending in the summon pulse (visual only). */
    public static void demo(int warnTicks) {
        onPacket(AtmoPacket.WAR_WARNING, warnTicks, warnTicks);
        demoSummonAt = Atmosphere.ticks() + warnTicks;
    }

    static void tick() {
        if (demoSummonAt >= 0 && Atmosphere.ticks() >= demoSummonAt) {
            demoSummonAt = -1;
            onPacket(AtmoPacket.WAR_SUMMON, 0, 0);
        }
        if (summonTicks > 0) summonTicks--;
        if (warnEnd >= 0 && Atmosphere.ticks() > warnEnd + 200) warnStart = warnEnd = -1;
    }

    /** 0..1 build-up during the warning; a constant 0.5 inside the arena. */
    public static float turbulence() {
        if (!AtmoProfile.of(MoonType.WAR).flag("preBattleBuildup")) return Atmosphere.inArena() ? 0.5f : 0f;
        if (warnEnd >= 0 && warnEnd > warnStart) {
            float p = (Atmosphere.ticks() - warnStart) / (float) (warnEnd - warnStart);
            return Math.max(0f, Math.min(1f, p));
        }
        return Atmosphere.inArena() ? 0.5f : 0f;
    }

    public static boolean warning() {
        return warnEnd >= 0;
    }

    /** Extra brightness of cloud flashes during the summon pulse (0..1). */
    public static float summonFlash() {
        return summonTicks / 40f;
    }

    /** War sky gets darker as the battle approaches (multiplier for the sky colour). */
    static float skyDarken(MoonType t) {
        return t == MoonType.WAR ? 1f - 0.3f * turbulence() : 1f;
    }

    /** Average ticks between distant orange cloud flashes. */
    static int flashInterval() {
        return (int) (240 - 200 * turbulence());
    }
}
