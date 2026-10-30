package net.ecmoons.feature.thread;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntitySize;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Pose;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Knotbind's yarn: a small, fast rose-coloured projectile with ordinary block collision, a short bounded life (it can
 * travel at most the configured range) and no gravity. It stops at the first block or living entity on its path,
 * never moves into an unloaded chunk and is never saved. Only the server decides hits.
 */
public class YarnEntity extends Entity {
    @Nullable public UUID owner;
    public int life;

    public YarnEntity(EntityType<? extends YarnEntity> type, World world) {
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
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void tick() {
        xo = getX();
        yo = getY();
        zo = getZ();
        super.tick();
        Vector3d v = getDeltaMovement();
        Vector3d from = position(), to = from.add(v);
        if (level.isClientSide) {
            setPos(to.x, to.y, to.z);
            return;
        }
        if (--life < 0 || !level.isLoaded(new BlockPos(to))) {
            remove();
            return;
        }
        BlockRayTraceResult block = level.clip(new RayTraceContext(from, to, RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, this));
        Vector3d end = block.getType() == RayTraceResult.Type.MISS ? to : block.getLocation();
        // first living entity on the path (never the thrower)
        Entity hit = null;
        double best = Double.MAX_VALUE;
        List<Entity> list = level.getEntities(this, new AxisAlignedBB(from, end).inflate(0.6), e -> e instanceof LivingEntity && e.isAlive()
                && !e.isSpectator() && (owner == null || !owner.equals(e.getUUID())));
        for (Entity e : list) {
            java.util.Optional<Vector3d> p = e.getBoundingBox().inflate(0.3).clip(from, end);
            if (p.isPresent() && p.get().distanceToSqr(from) < best) {
                best = p.get().distanceToSqr(from);
                hit = e;
            }
        }
        if (hit != null) {
            Knotbind.onYarnHit(this, (LivingEntity) hit);
            remove();
            return;
        }
        if (block.getType() != RayTraceResult.Type.MISS) {
            remove();
            return;
        }
        setPos(to.x, to.y, to.z);
    }
}
