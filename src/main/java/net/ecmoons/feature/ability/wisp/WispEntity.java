package net.ecmoons.feature.ability.wisp;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntitySize;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Pose;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.network.NetworkHooks;

/**
 * The small blue scouting wisp a player pilots during the Wisp Moon. It has no AI, can't attack, pick up, open or
 * activate anything; it only moves where the server moves it (with normal block collision, so no walls or closed
 * doors) and is removed as soon as nobody controls it. Any hit destroys it, which ends piloting.
 */
public class WispEntity extends Entity {
    /** Client only: the entity id the local player is piloting (its movement is predicted locally). */
    public static int localPilotId = -1;

    public WispEntity(EntityType<? extends WispEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    protected void readAdditionalSaveData(CompoundNBT nbt) {}

    @Override
    protected void addAdditionalSaveData(CompoundNBT nbt) {}

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    protected float getEyeHeight(Pose pose, EntitySize size) {
        return size.height * 0.5F;
    }

    @Override
    public boolean isPickable() {
        return !removed;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source)) return false;
        if (!level.isClientSide && !removed) {
            if (level instanceof net.minecraft.world.server.ServerWorld)
                ((net.minecraft.world.server.ServerWorld) level).sendParticles(ParticleTypes.SOUL, getX(), getY(), getZ(), 6, 0.15, 0.15, 0.15, 0.02);
            remove(); // the pilot notices on the next tick and returns to their body
        }
        return true;
    }

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide) {
            if (random.nextInt(8) == 0) {
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, getX() + (random.nextDouble() - 0.5) * 0.2,
                        getY() + 0.15 + random.nextDouble() * 0.1, getZ() + (random.nextDouble() - 0.5) * 0.2, 0, 0.01, 0);
            }
        } else if (!net.ecmoons.feature.ability.WispAbility.isControlled(this)) {
            remove();
        }
    }

    /** The pilot's client predicts its own wisp; small server corrections are ignored, large ones snap. */
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        if (level.isClientSide && getId() == localPilotId && distanceToSqr(x, y, z) < 2.25) return;
        super.lerpTo(x, y, z, yRot, xRot, steps, teleport);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 128 * 128;
    }

    // ---- movement shared by the server and the pilot's client prediction -------------------------------------------

    /** Wanted movement for one tick: like creative flight (horizontal by yaw; jump/sneak = up/down). */
    public static Vector3d motion(float forward, float strafe, float up, float yaw, double speed) {
        forward = MathHelper.clamp(forward, -1, 1);
        strafe = MathHelper.clamp(strafe, -1, 1);
        up = MathHelper.clamp(up, -1, 1);
        double len = Math.sqrt(forward * forward + strafe * strafe);
        if (len > 1) {
            forward /= len;
            strafe /= len;
        }
        double r = Math.toRadians(yaw);
        double sin = Math.sin(r), cos = Math.cos(r);
        return new Vector3d((strafe * cos - forward * sin) * speed, up * speed, (forward * cos + strafe * sin) * speed);
    }

    /** Clamp a wanted move so the wisp stays within range of the body. */
    public static Vector3d clampToRange(Vector3d pos, Vector3d delta, Vector3d body, double range) {
        Vector3d target = pos.add(delta);
        Vector3d off = target.subtract(body);
        if (off.lengthSqr() <= range * range) return delta;
        return body.add(off.normalize().scale(range)).subtract(pos); // slide along the edge, never past it
    }
}
