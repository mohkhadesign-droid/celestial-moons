package net.ecmoons.mixin.client;

import net.ecmoons.client.atmo.Atmosphere;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Atmosphere sky colour: blends the (vanilla or other mods') night sky colour towards the active moon's sky colour.
 * It modifies the returned value instead of replacing the method, so other sky-colour hooks still apply first.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true, require = 0)
    private void ecmoons$atmosphereSky(BlockPos pos, float partialTicks, CallbackInfoReturnable<Vector3d> cir) {
        Vector3d v = cir.getReturnValue();
        Vector3d out = Atmosphere.skyColor((ClientWorld) (Object) this, v, partialTicks);
        if (out != v) cir.setReturnValue(out);
    }
}
