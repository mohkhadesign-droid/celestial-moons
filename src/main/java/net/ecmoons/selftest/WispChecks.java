package net.ecmoons.selftest;

import com.mojang.authlib.GameProfile;
import net.ecmoons.MoonType;
import net.ecmoons.feature.ability.MoonAbilities;
import net.ecmoons.feature.ability.WispAbility;
import net.ecmoons.feature.ability.wisp.ObserverEntity;
import net.ecmoons.feature.ability.wisp.WispEntity;
import net.minecraft.block.Blocks;
import net.minecraft.entity.projectile.AbstractArrowEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.BlockEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Wisp Moon server rules with a fake player on the dedicated server: piloting, wall collision, range limit,
 * chunk-loading limits, observer placement (limit, protection), destruction by an arrow, viewing, damage and
 * cleanup. Camera switching needs a real client and is covered by the client test.
 */
final class WispChecks {
    private static FakePlayer fp;
    private static int bx, by, bz;
    private static double startX;
    private static AxisAlignedBB protectedBox;
    private static boolean listening;

    private WispChecks() {}

    /** Stands in for a protection mod (Flan, World Protector): refuses block placement inside protectedBox. */
    public static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (protectedBox != null && protectedBox.contains(e.getPos().getX() + 0.5, e.getPos().getY() + 0.5, e.getPos().getZ() + 0.5)) e.setCanceled(true);
    }

    private static void fill(ServerWorld w, int x0, int y0, int z0, int x1, int y1, int z1, net.minecraft.block.BlockState st) {
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) w.setBlockAndUpdate(new BlockPos(x, y, z), st);
    }

    private static void input(float forward, float yaw, float pitch) {
        WispAbility.onInput(fp, forward, 0, 0, yaw, pitch);
    }

    /** Called with the Wisp Moon up. A stone floor and, 6 blocks east of the body, a stone wall. */
    static void setup(ServerWorld w, BiConsumer<String, Boolean> check) {
        if (!listening) {
            MinecraftForge.EVENT_BUS.addListener(WispChecks::onPlace);
            listening = true;
        }
        BlockPos sp = w.getSharedSpawnPos();
        bx = sp.getX();
        bz = sp.getZ() + 8;
        by = w.getHeight(Heightmap.Type.MOTION_BLOCKING, bx, bz) + 30;
        fill(w, bx - 3, by - 1, bz - 3, bx + 8, by - 1, bz + 3, Blocks.STONE.defaultBlockState());
        fill(w, bx + 6, by, bz - 3, bx + 6, by + 4, bz + 3, Blocks.STONE.defaultBlockState());
        fp = FakePlayerFactory.get(w, new GameProfile(UUID.randomUUID(), "ecm_wisp"));
        new net.minecraft.network.play.ServerPlayNetHandler(w.getServer(),
                new net.minecraft.network.NetworkManager(net.minecraft.network.PacketDirection.SERVERBOUND), fp);
        fp.moveTo(bx + 0.5, by, bz + 0.5, -90, 0);
        WispAbility.TEST_PLAYERS.put(fp.getUUID(), fp);
        MoonAbilities.reset(fp);
        WispAbility.onAction(fp, WispAbility.PILOT, 0);
        WispEntity wisp = WispAbility.wispOf(fp);
        check.accept("wisp: piloting starts and a wisp entity appears at the player's eyes", WispAbility.piloting(fp) && wisp != null
                && wisp.isAlive() && Math.abs(wisp.getY() + wisp.getBbHeight() / 2 - fp.getEyeY()) < 0.05);
        check.accept("wisp: the player's game mode is unchanged (not spectator)", !fp.isSpectator());
        startX = wisp == null ? 0 : wisp.getX();
        input(1, -90, 0); // forward = east, towards the wall
    }

    static void afterFlight(ServerWorld w, BiConsumer<String, Boolean> check) {
        WispEntity wisp = WispAbility.wispOf(fp);
        double x = wisp == null ? 0 : wisp.getX();
        check.accept(String.format("wisp: flies under control and stops at a wall (x %.2f, wall face %d)", x - bx, 6),
                wisp != null && x - startX > 3 && x + wisp.getBbWidth() / 2 <= bx + 6 + 1e-3);
        // back off a little and look at the wall: place three observers at different heights
        input(-1, -90, 0);
    }

    static void place(ServerWorld w, BiConsumer<String, Boolean> check) {
        input(0, -90, 0);
        WispAbility.onAction(fp, WispAbility.PLACE, 0);
        input(0, -90, -30);
        WispAbility.onAction(fp, WispAbility.PLACE, 0);
        input(0, -90, 30);
        WispAbility.onAction(fp, WispAbility.PLACE, 0);
        List<ObserverEntity> obs = WispAbility.observers(fp);
        boolean onWall = !obs.isEmpty();
        for (ObserverEntity o : obs) onWall &= Math.abs(o.getX() + o.getBbWidth() / 2 - (bx + 6)) < 0.1 && o.yRot == 90F;
        check.accept("wisp: three observers placed on the wall face, facing away from it (" + obs.size() + ")", obs.size() == 3 && onWall);
        input(0, -90, 10);
        WispAbility.onAction(fp, WispAbility.PLACE, 0);
        check.accept("wisp: a fourth observer is refused at the limit", WispAbility.observers(fp).size() == 3);
        // protected area: placement there is refused (stand-in for a claim)
        WispAbility.onAction(fp, WispAbility.DISMISS, 3);
        check.accept("wisp: dismissing observer 3 leaves 2", WispAbility.observers(fp).size() == 2);
        protectedBox = new AxisAlignedBB(bx + 4, by - 2, bz - 4, bx + 7, by + 6, bz + 4);
        input(0, -90, 30);
        WispAbility.onAction(fp, WispAbility.PLACE, 0);
        check.accept("wisp: no observer where the owner may not place blocks (claims, protected areas)", WispAbility.observers(fp).size() == 2);
        protectedBox = null;
        // nothing in the air
        input(0, 90, 0);
        WispAbility.onAction(fp, WispAbility.PLACE, 0);
        check.accept("wisp: no observer without a block face in reach", WispAbility.observers(fp).size() == 2);
        // an arrow destroys observer 1
        ObserverEntity o1 = null;
        for (ObserverEntity o : WispAbility.observers(fp)) if (o.number == 1) o1 = o;
        if (o1 != null) {
            // along the wall (from the side), so it can't hit the wisp first
            ArrowEntity a = new ArrowEntity(w, o1.getX(), o1.getY() + 0.2, o1.getZ() - 3);
            a.setDeltaMovement(0, 0, 1.5);
            a.setNoGravity(true);
            a.pickup = AbstractArrowEntity.PickupStatus.DISALLOWED;
            w.addFreshEntity(a);
        }
        // fly straight up for longer than the range allows
        WispAbility.onInput(fp, 0, 0, 1, -90, 0);
    }

    static void afterArrow(ServerWorld w, BiConsumer<String, Boolean> check) {
        List<ObserverEntity> obs = WispAbility.observers(fp);
        boolean one = false;
        for (ObserverEntity o : obs) one |= o.number == 1;
        check.accept("wisp: an arrow destroys an observer (" + obs.size() + " left)", obs.size() == 1 && !one);
    }

    static void afterRange(ServerWorld w, BiConsumer<String, Boolean> check) {
        WispEntity wisp = WispAbility.wispOf(fp);
        double d = wisp == null ? -1 : wisp.position().add(0, wisp.getBbHeight() / 2, 0).distanceTo(new Vector3d(fp.getX(), fp.getEyeY(), fp.getZ()));
        check.accept(String.format("wisp: never further than 48 blocks from the body (%.2f after flying 60 up)", d), d > 47 && d <= 48.01);
        check.accept("wisp: unloaded or distant chunks are off limits (2000 blocks away)",
                !WispAbility.loadedForTest(fp, new Vector3d(fp.getX() + 2000, fp.getY(), fp.getZ())));
        // damage to the body ends piloting at once (fake players ignore real damage, so the event is posted directly)
        MinecraftForge.EVENT_BUS.post(new LivingHurtEvent(fp, DamageSource.GENERIC, 1F));
        check.accept("wisp: damage to the body ends piloting and removes the wisp", !WispAbility.piloting(fp) && wisp != null && wisp.removed);
        check.accept("wisp: the pilot cooldown starts", MoonAbilities.cooldownLeft(fp, MoonType.WISP) > 0);
        // view the remaining observer, then move the body: back to the body
        int n = WispAbility.observers(fp).isEmpty() ? 0 : WispAbility.observers(fp).get(0).number;
        WispAbility.onAction(fp, WispAbility.VIEW, n);
        check.accept("wisp: viewing an observer in range", WispAbility.viewing(fp) && WispAbility.viewedBy(fp) != null);
        fp.moveTo(fp.getX() + 2, fp.getY(), fp.getZ(), fp.yRot, fp.xRot);
    }

    static void afterBodyMoved(ServerWorld w, BiConsumer<String, Boolean> check) {
        check.accept("wisp: moving the body ends viewing", !WispAbility.viewing(fp));
        // out of range: the body 60 blocks away
        fp.moveTo(bx + 0.5 - 60, by, bz + 0.5, -90, 0);
        int n = WispAbility.observers(fp).isEmpty() ? 0 : WispAbility.observers(fp).get(0).number;
        WispAbility.onAction(fp, WispAbility.VIEW, n);
        check.accept("wisp: an observer more than 48 blocks away can't be viewed", !WispAbility.viewing(fp));
        fp.moveTo(bx + 0.5, by, bz + 0.5, -90, 0);
        // logout: the player's observers go
        MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedOutEvent(fp));
        check.accept("wisp: logging out removes the player's observers", WispAbility.observers(fp).isEmpty());
        // moon end: pilot and observers are cleaned up
        MoonAbilities.reset(fp);
        WispAbility.onAction(fp, WispAbility.PILOT, 0);
        input(1, -90, 0); // to the wall again
    }

    static void beforeMoonEnd(ServerWorld w, BiConsumer<String, Boolean> check) {
        input(0, -90, 0);
        WispAbility.onAction(fp, WispAbility.PLACE, 0);
        check.accept("wisp: piloting and one observer before the moon ends", WispAbility.piloting(fp) && WispAbility.observers(fp).size() == 1);
    }

    static void afterMoonEnd(ServerWorld w, BiConsumer<String, Boolean> check) {
        List<net.minecraft.entity.Entity> left = w.getEntities((net.minecraft.entity.Entity) null,
                new AxisAlignedBB(bx - 60, by - 10, bz - 60, bx + 60, by + 60, bz + 60),
                e -> (e instanceof WispEntity || e instanceof ObserverEntity) && e.isAlive());
        check.accept("wisp: when the moon ends piloting stops and every observer and wisp entity is gone (pilots "
                + WispAbility.pilotCount() + ", observers " + WispAbility.observerCount() + ", entities left " + left + ")",
                !WispAbility.piloting(fp) && WispAbility.observerCount() == 0 && left.isEmpty());
        fill(w, bx - 3, by - 1, bz - 3, bx + 8, by + 4, bz + 3, Blocks.AIR.defaultBlockState());
        WispAbility.TEST_PLAYERS.clear();
    }
}
