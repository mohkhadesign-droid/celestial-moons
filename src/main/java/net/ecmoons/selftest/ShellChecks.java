package net.ecmoons.selftest;

import net.ecmoons.feature.ability.ShellAbility;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.item.EnderPearlEntity;
import net.minecraft.entity.projectile.AbstractArrowEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PotionEntity;
import net.minecraft.entity.projectile.SmallFireballEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.PotionUtils;
import net.minecraft.potion.Potions;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Shell Moon projectile rules on the dedicated server, with real projectile entities: a shell (radius 3) floats in
 * the air with target mobs inside and outside, and projectiles are fired across the boundary in both directions.
 */
final class ShellChecks {
    private static Vector3d c, c2;
    private static int shell, shell2, before;
    private static MobEntity zin, zout, zfar, zpearl, pig, z2;
    private static ArrowEntity away;
    private static Vector3d pearlOwnerPos;

    private ShellChecks() {}

    private static <T extends MobEntity> T mob(ServerWorld w, EntityType<T> type, Vector3d at) {
        T m = type.create(w);
        m.moveTo(at.x, at.y, at.z, 0, 0);
        m.setNoAi(true);
        m.setNoGravity(true);
        m.setPersistenceRequired();
        w.addFreshEntity(m);
        return m;
    }

    private static ArrowEntity arrow(ServerWorld w, Vector3d at, Vector3d v) {
        ArrowEntity a = new ArrowEntity(w, at.x, at.y, at.z);
        a.setDeltaMovement(v);
        a.setNoGravity(true);
        a.pickup = AbstractArrowEntity.PickupStatus.ALLOWED;
        a.setBaseDamage(4.0);
        w.addFreshEntity(a);
        return a;
    }

    static void setup(ServerWorld w, BiConsumer<String, Boolean> check) {
        BlockPos sp = w.getSharedSpawnPos();
        int y = w.getHeight(Heightmap.Type.MOTION_BLOCKING, sp.getX(), sp.getZ()) + 40;
        c = new Vector3d(sp.getX() + 0.5, y, sp.getZ() + 0.5);
        before = ShellAbility.intercepted;
        shell = ShellAbility.spawnForTest(w, c, 3.0, 600, 1000, UUID.randomUUID());
        zin = mob(w, EntityType.HUSK, c.add(0, -0.9, 0));
        zout = mob(w, EntityType.HUSK, c.add(6, -0.9, -1.5));
        zfar = mob(w, EntityType.HUSK, c.add(-11, -0.9, 1.5));
        zpearl = mob(w, EntityType.HUSK, c.add(9, -0.9, 4));
        pearlOwnerPos = zpearl.position();
        pig = mob(w, EntityType.PIG, c.add(-6, -0.4, -1.5));
        // A: outside -> in, at the husk inside
        arrow(w, c.add(6, 0, 0), new Vector3d(-1.5, 0, 0));
        // B: inside -> out, at the husk outside
        arrow(w, c.add(0.5, 0, -1.5), new Vector3d(1.5, 0, 0));
        // C: very fast, would cross the whole shell in one tick (24 blocks/tick) towards a husk far behind it
        arrow(w, c.add(12, 0, 1.5), new Vector3d(-24, 0, 0));
        // D: entirely outside, moving away: never touched
        away = arrow(w, c.add(4, 0, -4.5), new Vector3d(1, 0, 0)); // clear of every mob's lane
        // pearl from outside, owned by a husk: if it got through, the husk would be teleported in
        EnderPearlEntity pearl = new EnderPearlEntity(EntityType.ENDER_PEARL, w);
        pearl.setOwner(zpearl);
        pearl.moveTo(c.x + 6, c.y + 1.2, c.z, 0, 0);
        pearl.setDeltaMovement(-1.5, 0, 0);
        pearl.setNoGravity(true);
        w.addFreshEntity(pearl);
        // splash potion of harming from inside, out at the pig
        PotionEntity potion = new PotionEntity(EntityType.POTION, w);
        potion.setItem(PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.STRONG_HARMING));
        potion.moveTo(c.x - 0.5, c.y - 0.2, c.z - 1.5, 0, 0);
        potion.setDeltaMovement(-1.2, 0, 0);
        potion.setNoGravity(true);
        w.addFreshEntity(potion);
        // small fireball from above, down at the husk inside
        SmallFireballEntity fb = new SmallFireballEntity(w, c.x, c.y + 6, c.z, 0, -1, 0);
        fb.setDeltaMovement(0, -1.2, 0);
        w.addFreshEntity(fb);
        // trident from outside, inward (low lane)
        TridentEntity tri = new TridentEntity(w, zfar, new ItemStack(Items.TRIDENT));
        tri.moveTo(c.x + 6, c.y - 1.2, c.z + 0.6, 0, 0);
        tri.setDeltaMovement(-2, 0, 0);
        tri.setNoGravity(true);
        tri.pickup = AbstractArrowEntity.PickupStatus.ALLOWED;
        w.addFreshEntity(tri);
        check.accept("shell: test shell active", ShellAbility.active(shell));
    }

    static void verify(ServerWorld w, BiConsumer<String, Boolean> check) {
        AxisAlignedBB area = new AxisAlignedBB(c.add(-16, -64, -8), c.add(16, 8, 8)); // dropped items fall to the ground
        int arrowItems = 0, tridentItems = 0, flyingArrows = 0, tridents = 0;
        for (Entity e : w.getEntities((Entity) null, area, e -> true)) {
            if (e instanceof ItemEntity) {
                ItemStack st = ((ItemEntity) e).getItem();
                if (st.getItem() == Items.ARROW) arrowItems += st.getCount();
                if (st.getItem() == Items.TRIDENT) tridentItems += st.getCount();
            }
            if (e instanceof ArrowEntity && e != away) flyingArrows++;
            if (e instanceof TridentEntity) tridents++;
        }
        int n = ShellAbility.intercepted - before;
        check.accept("shell: arrow fired from outside never reaches the mob inside (" + zin.getHealth() + "/" + zin.getMaxHealth() + ")",
                zin.getHealth() == zin.getMaxHealth());
        check.accept("shell: arrow fired from inside never reaches the mob outside", zout.getHealth() == zout.getMaxHealth());
        check.accept("shell: a very fast arrow crossing the whole shell in one tick is stopped", zfar.getHealth() == zfar.getMaxHealth());
        check.accept("shell: an arrow entirely outside, moving away, is untouched", away.isAlive());
        check.accept("shell: an intercepted ender pearl teleports nobody", zpearl.position().distanceTo(pearlOwnerPos) < 0.01);
        check.accept("shell: an intercepted splash potion splashes nobody outside (pig " + pig.getHealth() + ")", pig.getHealth() == pig.getMaxHealth());
        check.accept("shell: an intercepted fireball burns and explodes nothing inside", zin.getRemainingFireTicks() <= 0 && zin.getHealth() == zin.getMaxHealth());
        check.accept("shell: each pickup-able arrow comes back as exactly one arrow item (" + arrowItems + " items, " + flyingArrows + " arrows left)",
                arrowItems == 3 && flyingArrows == 0);
        check.accept("shell: the trident is dropped as one trident item, never duplicated (" + tridentItems + " items, " + tridents + " entities)",
                tridentItems == 1 && tridents == 0);
        check.accept("shell: 7 projectiles intercepted (" + n + ")", n == 7);
        check.accept("shell: durability used, fireball costs more (" + ShellAbility.durability(shell) + " = 1000 - 6x10 - 30)",
                ShellAbility.durability(shell) == 910);
        // exhaustion: a new shell with 15 durability - two arrows are stopped, then it collapses and the third gets through
        BlockPos sp = w.getSharedSpawnPos();
        c2 = c.add(0, 0, 30);
        shell2 = ShellAbility.spawnForTest(w, c2, 3.0, 600, 15, UUID.randomUUID());
        z2 = mob(w, EntityType.HUSK, c2.add(0, -0.9, 0));
        arrow(w, c2.add(6, 0, 0), new Vector3d(-1.5, 0, 0));
        arrow(w, c2.add(6, 0.4, 0), new Vector3d(-1.5, 0, 0)).setPos(c2.x + 9, c2.y + 0.4, c2.z);
    }

    static void verifyExhaust(ServerWorld w, BiConsumer<String, Boolean> check) {
        check.accept("shell: the hit that exhausts the durability is still stopped, then the shell collapses (active " + ShellAbility.active(shell2) + ")",
                !ShellAbility.active(shell2) && z2.getHealth() == z2.getMaxHealth());
        arrow(w, c2.add(6, -0.3, 0), new Vector3d(-1.5, 0, 0));
    }

    static void verifyAfterCollapse(ServerWorld w, BiConsumer<String, Boolean> check) {
        check.accept("shell: after collapsing, projectiles pass again (husk hit: " + z2.getHealth() + "/" + z2.getMaxHealth() + ")",
                z2.getHealth() < z2.getMaxHealth());
        for (LivingEntity e : new LivingEntity[]{zin, zout, zfar, zpearl, pig, z2}) e.remove();
        ShellAbility.clearAll();
    }
}
