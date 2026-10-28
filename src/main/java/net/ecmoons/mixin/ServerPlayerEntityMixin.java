package net.ecmoons.mixin;

import net.ecmoons.feature.war.WarFeatures;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * War Moon: inside a running tournament, two fighters may always hurt each other (even with server PvP off or
 * shared scoreboard teams), and nobody else involved (spectators, knocked-out players, fighters during
 * preparation) can hurt or be hurt by players. Outside the tournament this does nothing.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    @Inject(method = "canHarmPlayer", at = @At("HEAD"), cancellable = true)
    private void ecmoons$warPvp(PlayerEntity other, CallbackInfoReturnable<Boolean> cir) {
        Boolean result = WarFeatures.pvpOverride((ServerPlayerEntity) (Object) this, other);
        if (result != null) cir.setReturnValue(result);
    }
}
