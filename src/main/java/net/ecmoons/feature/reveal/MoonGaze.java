package net.ecmoons.feature.reveal;

import net.minecraft.block.BlockState;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Quaternion;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.world.World;

/**
 * Where the moon is in the sky, computed exactly like vanilla's sky renderer places it (WorldRenderer.renderSky:
 * rotate Y by -90 degrees, then X by timeOfDay * 360; the moon quad sits at -Y), so the server, the client and the
 * rendered eye always agree. Shared by Binding Gaze (server) and the client's own release prediction.
 */
public final class MoonGaze {
    private MoonGaze() {}

    /** Unit vector from the viewer towards the centre of the rendered moon. */
    public static Vector3d moonDirection(World world, float partialTicks) {
        return moonDirection(world.getTimeOfDay(partialTicks));
    }

    public static Vector3d moonDirection(float timeOfDay) {
        // Vector3f.XP.rotationDegrees is client-only; this constructor and transform exist on both sides
        Vector3f v = new Vector3f(0f, -1f, 0f);
        v.transform(new Quaternion(new Vector3f(1f, 0f, 0f), timeOfDay * 360f, true));
        v.transform(new Quaternion(new Vector3f(0f, 1f, 0f), -90f, true));
        return new Vector3d(v.x(), v.y(), v.z()).normalize();
    }

    /** Angle in degrees between the entity's look direction and the moon. */
    public static double angleToMoon(Entity viewer, Vector3d moon) {
        Vector3d look = viewer.getViewVector(1f);
        double dot = MathHelper.clamp(look.dot(moon), -1.0, 1.0);
        return Math.toDegrees(Math.acos(dot));
    }

    /** True when the moon is above the horizon and the look is inside the cone around its centre. */
    public static boolean lookingAtMoon(Entity viewer, Vector3d moon, double coneDegrees) {
        return moon.y > 0.02 && angleToMoon(viewer, moon) <= coneDegrees;
    }

    /**
     * Is the sky open from this eye position towards the moon? Walks the blocks along the ray (voxel traversal)
     * until it leaves the world's build height. Opaque blocks and leaves block the view; glass and other
     * see-through blocks do not. Only already-loaded chunks are read: the ray stops (counts as open) at the first
     * unloaded chunk, so this never loads chunks. A downward or horizontal direction never sees the sky.
     */
    public static boolean skyOpen(World world, Vector3d eye, Vector3d dir, int maxBlocks) {
        if (dir.y <= 0.02) return false;
        int top = 256; // 1.16 build height
        int x = MathHelper.floor(eye.x), y = MathHelper.floor(eye.y), z = MathHelper.floor(eye.z);
        int sx = dir.x > 0 ? 1 : -1, sy = 1, sz = dir.z > 0 ? 1 : -1;
        double tdx = Math.abs(dir.x) < 1e-9 ? Double.MAX_VALUE : Math.abs(1.0 / dir.x);
        double tdy = 1.0 / dir.y;
        double tdz = Math.abs(dir.z) < 1e-9 ? Double.MAX_VALUE : Math.abs(1.0 / dir.z);
        double tmx = Math.abs(dir.x) < 1e-9 ? Double.MAX_VALUE : ((sx > 0 ? (x + 1 - eye.x) : (eye.x - x)) * tdx);
        double tmy = (y + 1 - eye.y) * tdy;
        double tmz = Math.abs(dir.z) < 1e-9 ? Double.MAX_VALUE : ((sz > 0 ? (z + 1 - eye.z) : (eye.z - z)) * tdz);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int i = 0; i < maxBlocks; i++) {
            if (tmx < tmy && tmx < tmz) { x += sx; tmx += tdx; }
            else if (tmy < tmz) { y += sy; tmy += tdy; }
            else { z += sz; tmz += tdz; }
            if (y >= top) return true;
            if (y < 0) continue;
            pos.set(x, y, z);
            if (!world.isLoaded(pos)) return true;
            if (blocksView(world.getBlockState(pos))) return false;
        }
        return true;
    }

    static boolean blocksView(BlockState state) {
        return state.canOcclude() || state.getMaterial() == Material.LEAVES;
    }
}
