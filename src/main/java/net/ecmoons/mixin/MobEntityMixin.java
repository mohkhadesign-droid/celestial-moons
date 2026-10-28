package net.ecmoons.mixin;

import net.ecmoons.feature.SpawnOrigins;
import net.minecraft.entity.ILivingEntityData;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.IServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records the vanilla SpawnReason of every mob that is finalised (natural, egg, command, breeding...). */
@Mixin(MobEntity.class)
public abstract class MobEntityMixin {
    @Inject(method = "finalizeSpawn", at = @At("HEAD"))
    private void ecmoons$recordOrigin(IServerWorld world, DifficultyInstance difficulty, SpawnReason reason,
                                      ILivingEntityData data, CompoundNBT tag, CallbackInfoReturnable<ILivingEntityData> cir) {
        SpawnOrigins.onFinalizeSpawn((MobEntity) (Object) this, reason);
    }
}
