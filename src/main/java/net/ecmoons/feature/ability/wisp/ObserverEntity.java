package net.ecmoons.feature.ability.wisp;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntitySize;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Pose;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;
import net.minecraftforge.fml.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A stationary soul observer: a small cyan soul flame fixed on a block face. Its spawn rotation is the direction it
 * looks; whoever views it can turn only a limited amount from that. Everyone can see it, and any hit (melee,
 * projectile or explosion) that the server and protection mods allow destroys it.
 */
public class ObserverEntity extends Entity {
    /** Client only: the observer the local player is looking through (no particles in front of the lens). */
    public static int localViewId = -1;
    @Nullable public UUID owner;
    public int number;

    public ObserverEntity(EntityType<? extends ObserverEntity> type, World world) {
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

    /** Never presses pressure plates or trips tripwires (vanilla asks every entity this). */
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source) || removed) return false;
        if (!level.isClientSide) {
            remove();
            net.ecmoons.feature.ability.WispAbility.observerDestroyed(this, source);
        }
        return true;
    }

    @Override
    public void tick() {
        // stationary: no movement, no physics
        xo = getX();
        yo = getY();
        zo = getZ();
        if (level.isClientSide) {
            if (getId() != localViewId && random.nextInt(14) == 0) {
                level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, getX() + (random.nextDouble() - 0.5) * 0.15, getY() + 0.12,
                        getZ() + (random.nextDouble() - 0.5) * 0.15, 0, 0.008, 0);
            }
        } else if (!net.ecmoons.feature.ability.WispAbility.isRegistered(this)) {
            remove();
        }
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) {
        return d < 96 * 96;
    }
}
