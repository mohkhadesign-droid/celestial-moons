package net.ecmoons.mixin.client;

import net.minecraft.client.audio.LocatableSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Quiet Moon: read/scale a sound's configured volume before it is played (the resolved volume is not available yet). */
@Mixin(LocatableSound.class)
public interface LocatableSoundAccess {
    @Accessor("volume")
    float ecmoons$getVolume();

    @Accessor("volume")
    void ecmoons$setVolume(float volume);
}
