package net.ecmoons.mixin;

import net.ecmoons.feature.MistFeatures;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.server.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mist Moon: shrink the broadcast radius of footstep sounds. Only sounds whose id ends in ".step" are affected;
 * attacks, explosions, block breaking and every other sound keep their normal range.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldMixin {
    @Unique private SoundEvent ecmoons$pendingSound;

    @Inject(method = "playSound(Lnet/minecraft/entity/player/PlayerEntity;DDDLnet/minecraft/util/SoundEvent;Lnet/minecraft/util/SoundCategory;FF)V",
            at = @At("HEAD"))
    private void ecmoons$captureSound(PlayerEntity player, double x, double y, double z, SoundEvent sound, SoundCategory category,
                                      float volume, float pitch, CallbackInfo ci) {
        ecmoons$pendingSound = sound;
    }

    @ModifyArg(method = "playSound(Lnet/minecraft/entity/player/PlayerEntity;DDDLnet/minecraft/util/SoundEvent;Lnet/minecraft/util/SoundCategory;FF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/management/PlayerList;broadcast(Lnet/minecraft/entity/player/PlayerEntity;DDDDLnet/minecraft/util/RegistryKey;Lnet/minecraft/network/IPacket;)V"),
            index = 4)
    private double ecmoons$footstepRadius(double radius) {
        SoundEvent sound = ecmoons$pendingSound;
        ecmoons$pendingSound = null;
        return MistFeatures.footstepRadius((ServerWorld) (Object) this, sound, radius);
    }
}
