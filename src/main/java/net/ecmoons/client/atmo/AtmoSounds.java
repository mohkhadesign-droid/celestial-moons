package net.ecmoons.client.atmo;

import net.ecmoons.MoonType;
import net.ecmoons.config.ClientConfig;
import net.ecmoons.mixin.client.LocatableSoundAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.LocatableSound;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.SoundEvents;

import java.util.Random;

/**
 * Occasional, quiet ambient sounds per moon (vanilla sound events only, played locally) and the Quiet Moon's gentle
 * reduction of ambient/weather sounds. Players, mobs, combat, blocks, UI and music are never changed, and the
 * master volume is never touched.
 */
public final class AtmoSounds {
    private static final Random RANDOM = new Random();
    private static final long[] next = new long[MoonType.values().length];

    private AtmoSounds() {}

    static float clientVolume() {
        try {
            return ClientConfig.ATMOSPHERE_SOUND_VOLUME.get().floatValue();
        } catch (RuntimeException e) {
            return 1f;
        }
    }

    private static SoundEvent sound(MoonType t) {
        switch (t) {
            case BLOOD: return SoundEvents.AMBIENT_CRIMSON_FOREST_MOOD;
            case MIST: return SoundEvents.AMBIENT_CAVE;
            case ARCANE: return SoundEvents.PORTAL_AMBIENT;
            case WISDOM: return SoundEvents.NOTE_BLOCK_CHIME;
            case DRIFT: return SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT;
            case REVEALING: return SoundEvents.AMBIENT_SOUL_SAND_VALLEY_MOOD;
            case THREAD: return SoundEvents.NOTE_BLOCK_HARP;
            case WAR: return SoundEvents.LIGHTNING_BOLT_THUNDER;
            case GLUTTONY: return SoundEvents.CAMPFIRE_CRACKLE;
            default: return null; // Blue, Harvest, Frost, Prospector, Quiet: silence is part of their identity
        }
    }

    private static int interval(MoonType t) {
        switch (t) {
            case WAR: return (int) (1600 - 1200 * AtmoWar.turbulence());
            case GLUTTONY: return 500;
            case WISDOM: return 700;
            default: return 1200;
        }
    }

    private static float pitch(MoonType t) {
        switch (t) {
            case WAR: return 0.5f;
            case ARCANE: return 1.4f;
            case WISDOM: return 0.8f + RANDOM.nextInt(4) * 0.25f;
            case THREAD: return 1.2f + RANDOM.nextInt(3) * 0.2f;
            default: return 0.9f;
        }
    }

    private static float baseVolume(MoonType t) {
        switch (t) {
            case WAR: return 0.35f;
            case ARCANE: return 0.25f;
            case WISDOM:
            case THREAD: return 0.18f;
            default: return 0.5f;
        }
    }

    static void tick(Minecraft mc, ClientWorld level) {
        if (!Atmosphere.on("enableAmbientSounds")) return;
        long now = Atmosphere.ticks();
        for (MoonType t : MoonType.MOONS) {
            float w = Atmosphere.weight(t);
            int i = t.ordinal();
            if (w < 0.9f) {
                next[i] = 0;
                continue;
            }
            SoundEvent ev = sound(t);
            AtmoProfile p = AtmoProfile.of(t);
            float vol = p.soundVolume * clientVolume() * baseVolume(t);
            if (ev == null || vol <= 0.001f) continue;
            if (next[i] == 0) next[i] = now + 200 + RANDOM.nextInt(interval(t));
            if (now < next[i]) continue;
            next[i] = now + interval(t) / 2 + RANDOM.nextInt(interval(t));
            double ang = RANDOM.nextDouble() * Math.PI * 2, dist = t == MoonType.WAR ? 40 : 10;
            mc.getSoundManager().play(new SimpleSound(ev, SoundCategory.AMBIENT, vol, pitch(t),
                    mc.player.getX() + Math.cos(ang) * dist, mc.player.getY() + 4, mc.player.getZ() + Math.sin(ang) * dist));
        }
    }

    /** Quiet Moon: lower ambient and weather sounds only (PlaySoundEvent). */
    public static ISound quieten(ISound sound) {
        float w = Atmosphere.weight(MoonType.QUIET);
        if (w <= 0f || !(sound instanceof LocatableSound) || !Atmosphere.on("enableAmbientSounds")) return sound;
        SoundCategory c = sound.getSource();
        if (c != SoundCategory.AMBIENT && c != SoundCategory.WEATHER) return sound;
        float reduce = (float) AtmoProfile.of(MoonType.QUIET).value("ambientSoundReduction", 0.6) * w;
        LocatableSoundAccess acc = (LocatableSoundAccess) sound;
        acc.ecmoons$setVolume(acc.ecmoons$getVolume() * (1f - reduce));
        return sound;
    }
}
