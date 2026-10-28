package net.ecmoons.feature.ability;

import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.config.Matchers;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.PlacedBlocks;
import net.ecmoons.mixin.AbstractArrowEntityAccess;
import net.ecmoons.net.AbilityFxPacket;
import net.ecmoons.net.AbilityStatePacket;
import net.ecmoons.net.Network;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.entity.projectile.AbstractArrowEntity;
import net.minecraft.entity.projectile.DamagingProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shell Moon - a stationary projectile barrier.
 * <p>
 * Activation: crouch and hold the Moon Ability key for chargeTicks (2 s). The shell (a sphere, radius 3) appears at
 * the caster's position and lasts while the caster keeps crouching, keeps holding the key and stays put, up to
 * durationTicks (6 s). Cooldown starts when it ends.
 * <p>
 * Projectiles (every {@link ProjectileEntity} - arrows, tridents, fireballs, potions, pearls, snowballs, fireworks,
 * shulker bullets, llama spit, fishing hooks and modded projectiles built on the vanilla classes - plus any entity
 * type listed in shell.extraProjectileEntities) can never cross the boundary, in either direction, whoever fired
 * them. The check runs at the start of every world tick on the straight path the projectile is about to travel
 * (so a fast projectile cannot jump across between updates), with two safety nets: an impact on the far side is
 * cancelled, and a projectile seen on the other side after its move is stopped. Intercepted projectiles are
 * removed without any impact effect (no splash, no teleport, no explosion); a pickup-able arrow or trident is
 * dropped as its item at the boundary (never duplicated, never lost). Each interception uses durability;
 * explosive projectiles use more; the hit that exhausts it is still stopped, then the shell collapses.
 * <p>
 * Players inside take explosionReduction (75%) less explosion damage. Walking, melee, blocks and all other damage
 * are unaffected. Overlapping shells are refused, so protection never stacks.
 */
public final class ShellAbility {
    static final class Shell {
        final int id;
        final UUID owner;
        final RegistryKey<World> dim;
        final Vector3d center, ownerPos;
        final double radius;
        final long expires;
        int durability;
        boolean collapsed;

        Shell(int id, UUID owner, RegistryKey<World> dim, Vector3d center, Vector3d ownerPos, double radius, long expires, int durability) {
            this.id = id;
            this.owner = owner;
            this.dim = dim;
            this.center = center;
            this.ownerPos = ownerPos;
            this.radius = radius;
            this.expires = expires;
            this.durability = durability;
        }

        boolean inside(Vector3d p) {
            return p.distanceToSqr(center) < radius * radius;
        }
    }

    private static final class Charge {
        final long start;
        final Vector3d pos;

        Charge(long start, Vector3d pos) {
            this.start = start;
            this.pos = pos;
        }
    }

    private static final List<Shell> SHELLS = new ArrayList<>();
    private static final Map<UUID, Charge> CHARGING = new HashMap<>();
    private static final Map<UUID, Boolean> HOLDING = new HashMap<>();
    /** projectile entity id -> was inside (per shell id) when first seen near it. */
    private static final Map<Long, Boolean> SIDE = new HashMap<>();
    private static int nextId = 1;
    public static int intercepted; // diagnostics / tests

    private ShellAbility() {}

    private static Settings s() {
        return Settings.server();
    }

    // ---- activation -----------------------------------------------------------------------------------------------

    static void onPress(ServerPlayerEntity p) {
        HOLDING.put(p.getUUID(), true);
        if (owned(p) != null) return; // already up; keep holding
        String why = MoonAbilities.blocked(p, MoonType.SHELL);
        if (why != null) {
            MoonAbilities.fail(p, why);
            return;
        }
        if (!p.isCrouching()) {
            MoonAbilities.fail(p, "Crouch and hold to raise a shell");
            return;
        }
        int cd = MoonAbilities.cooldownLeft(p, MoonType.SHELL);
        if (cd > 0) {
            MoonAbilities.fail(p, "Shell ready in " + (cd + 19) / 20 + "s");
            return;
        }
        if (p.isPassenger() || p.isFallFlying()) {
            MoonAbilities.fail(p, "Not while riding or flying");
            return;
        }
        CHARGING.put(p.getUUID(), new Charge(MoonAbilities.now(), p.position()));
        Network.send(p, new AbilityStatePacket((byte) 2, 1, 0, s().i("shell.chargeTicks"), 0));
    }

    static void onRelease(ServerPlayerEntity p) {
        HOLDING.remove(p.getUUID());
        if (CHARGING.remove(p.getUUID()) != null) {
            MoonAbilities.fail(p, "Shell cancelled");
            MoonAbilities.sendIdle(p);
        }
        // an active shell collapses on the next tick (the key is no longer held)
    }

    @Nullable
    private static Shell owned(PlayerEntity p) {
        for (Shell sh : SHELLS) if (!sh.collapsed && sh.owner.equals(p.getUUID())) return sh;
        return null;
    }

    private static boolean held(ServerPlayerEntity p) {
        return HOLDING.containsKey(p.getUUID());
    }

    /** Could this player place a block here? Asks the protection mods through Forge's own placement event. */
    static boolean allowedHere(ServerPlayerEntity p, BlockPos pos) {
        return !s().b("shell.respectProtection") || MoonAbilities.canPlace(p, pos);
    }

    // ---- per tick (server tick end) ---------------------------------------------------------------------------------

    static void tickEnd() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        long now = MoonAbilities.now();
        double tol = s().d("shell.moveTolerance");
        for (Iterator<Map.Entry<UUID, Charge>> it = CHARGING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Charge> e = it.next();
            ServerPlayerEntity p = server.getPlayerList().getPlayer(e.getKey());
            Charge c = e.getValue();
            if (p == null) {
                it.remove();
                continue;
            }
            String why = !held(p) ? "Shell cancelled" : !p.isCrouching() ? "Shell cancelled: you stood up"
                    : p.position().distanceTo(c.pos) > tol ? "Shell cancelled: you moved" : MoonAbilities.blocked(p, MoonType.SHELL);
            if (why != null) {
                it.remove();
                MoonAbilities.fail(p, why);
                MoonAbilities.sendIdle(p);
                continue;
            }
            if (now - c.start >= s().i("shell.chargeTicks")) {
                it.remove();
                deploy(p);
            }
        }
        for (Shell sh : new ArrayList<>(SHELLS)) {
            if (sh.collapsed) continue;
            ServerPlayerEntity p = server.getPlayerList().getPlayer(sh.owner);
            String why = null;
            if (TEST_SHELLS.contains(sh.id)) {
                if (now >= sh.expires) collapse(sh, "expired");
                continue;
            }
            if (p == null || !p.level.dimension().equals(sh.dim)) why = "owner left";
            else if (now >= sh.expires) why = "expired";
            else if (!held(p)) why = "released";
            else if (!p.isCrouching()) why = "stood up";
            else if (p.position().distanceTo(sh.ownerPos) > tol) why = "moved";
            else if (MoonAbilities.blocked(p, MoonType.SHELL) != null) why = "moon ended";
            if (why != null) collapse(sh, why);
            else if ((now - sh.expires) % 20 == 0) broadcast(sh, AbilityFxPacket.SHELL_ADD); // refresh for players who came near
        }
        SHELLS.removeIf(sh -> sh.collapsed);
        // safety net: a projectile that is now on the other side of a shell than where it was is stopped
        for (Shell sh : new ArrayList<>(SHELLS)) {
            ServerWorld w = server.getLevel(sh.dim);
            if (w == null) continue;
            for (Entity e : nearbyProjectiles(w, sh, 2)) {
                Boolean before = SIDE.get(key(sh, e));
                boolean now2 = sh.inside(e.position());
                if (before != null && before != now2) intercept(w, sh, e, e.position());
                else SIDE.put(key(sh, e), now2);
            }
        }
        if (SHELLS.isEmpty()) SIDE.clear();
    }

    private static void deploy(ServerPlayerEntity p) {
        double r = s().d("shell.radius");
        Vector3d center = p.position().add(0, 0.9, 0);
        for (Shell other : SHELLS) {
            if (!other.collapsed && other.dim.equals(p.level.dimension()) && other.center.distanceTo(center) < r + other.radius) {
                MoonAbilities.fail(p, "Too close to another shell");
                MoonAbilities.sendIdle(p);
                return;
            }
        }
        if (!allowedHere(p, p.blockPosition())) {
            MoonAbilities.fail(p, "You can't raise a shell here (protected area)");
            MoonAbilities.sendIdle(p);
            return;
        }
        long now = MoonAbilities.now();
        Shell sh = new Shell(nextId++, p.getUUID(), p.level.dimension(), center, p.position(), r, now + s().i("shell.durationTicks"),
                s().i("shell.durability"));
        SHELLS.add(sh);
        p.level.playSound(null, center.x, center.y, center.z, SoundEvents.BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.6f, 1.6f);
        broadcast(sh, AbilityFxPacket.SHELL_ADD);
        Network.send(p, new AbilityStatePacket((byte) 2, 2, s().i("shell.durationTicks"), sh.durability, s().i("shell.durability")));
    }

    static void collapse(Shell sh, String why) {
        if (sh.collapsed) return;
        sh.collapsed = true;
        broadcast(sh, AbilityFxPacket.SHELL_REMOVE);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayerEntity p = server == null ? null : server.getPlayerList().getPlayer(sh.owner);
        ServerWorld w = server == null ? null : server.getLevel(sh.dim);
        if (w != null) w.playSound(null, sh.center.x, sh.center.y, sh.center.z, SoundEvents.BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.6f, 1.4f);
        if (p != null) {
            MoonAbilities.startCooldown(p, MoonType.SHELL, s().i("shell.cooldownSeconds"));
            Network.send(p, new AbilityStatePacket((byte) 2, 0, 0, 0, MoonAbilities.cooldownLeft(p, MoonType.SHELL)));
        } else {
            // owner offline: cooldown is keyed by UUID and still applies when they return
            MoonAbilities.startCooldownFor(sh.owner, MoonType.SHELL, s().i("shell.cooldownSeconds"));
        }
        EcMoons.LOGGER.debug("Shell {} collapsed: {}", sh.id, why);
    }

    static void collapseOwned(ServerPlayerEntity p, String why) {
        CHARGING.remove(p.getUUID());
        HOLDING.remove(p.getUUID());
        Shell sh = owned(p);
        if (sh != null) collapse(sh, why);
        SHELLS.removeIf(x -> x.collapsed);
    }

    /** Admin: remove every shell. */
    public static int clearAll() {
        int n = 0;
        for (Shell sh : SHELLS) if (!sh.collapsed) {
            collapse(sh, "admin");
            n++;
        }
        SHELLS.clear();
        CHARGING.clear();
        HOLDING.clear();
        SIDE.clear();
        return n;
    }

    private static void broadcast(Shell sh, byte kind) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        int left = (int) Math.max(0, sh.expires - MoonAbilities.now());
        float frac = sh.durability / (float) Math.max(1, s().i("shell.durability"));
        Network.CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(sh.center.x, sh.center.y, sh.center.z, 96, sh.dim)),
                new AbilityFxPacket(kind, sh.id, sh.center.x, sh.center.y, sh.center.z, 0f, (float) sh.radius, left, frac));
    }

    // ---- projectiles -------------------------------------------------------------------------------------------------

    static boolean isProjectile(Entity e) {
        if (e instanceof ProjectileEntity) return true;
        List<String> extra = s().list("shell.extraProjectileEntities");
        if (extra.isEmpty()) return false;
        for (String entry : extra) if (Matchers.entity(s(), entry, e.getType())) return true;
        return false;
    }

    private static boolean explosive(Entity e) {
        return e instanceof DamagingProjectileEntity || e instanceof FireworkRocketEntity;
    }

    private static long key(Shell sh, Entity e) {
        return ((long) sh.id << 32) | (e.getId() & 0xffffffffL);
    }

    private static List<Entity> nearbyProjectiles(World w, Shell sh, double margin) {
        double r = sh.radius + margin;
        AxisAlignedBB box = new AxisAlignedBB(sh.center.x - r, sh.center.y - r, sh.center.z - r, sh.center.x + r, sh.center.y + r, sh.center.z + r);
        return w.getEntities((Entity) null, box, e -> e.isAlive() && isProjectile(e));
    }

    /**
     * Start of the world tick, before projectiles move: stop every projectile whose straight path for this tick
     * crosses a shell boundary (entering or leaving, or passing straight through).
     */
    static void interceptBeforeMove(World world) {
        if (SHELLS.isEmpty() || !(world instanceof ServerWorld)) return;
        ServerWorld w = (ServerWorld) world;
        for (Shell sh : new ArrayList<>(SHELLS)) {
            if (sh.collapsed || !sh.dim.equals(w.dimension())) continue;
            for (Entity e : nearbyProjectiles(w, sh, 12)) { // 12: a projectile up to this fast is caught before it jumps across
                Vector3d p0 = e.position(), v = e.getDeltaMovement(), p1 = p0.add(v);
                SIDE.putIfAbsent(key(sh, e), sh.inside(p0));
                Vector3d hit = crossing(sh, p0, p1);
                if (hit != null) intercept(w, sh, e, hit);
            }
        }
    }

    /** First point where the segment p0->p1 crosses the sphere surface, or null. */
    @Nullable
    static Vector3d crossing(Shell sh, Vector3d p0, Vector3d p1) {
        Vector3d d = p1.subtract(p0);
        double a = d.lengthSqr();
        if (a < 1e-9) return null;
        Vector3d f = p0.subtract(sh.center);
        double b = 2 * f.dot(d), c = f.lengthSqr() - sh.radius * sh.radius;
        double disc = b * b - 4 * a * c;
        if (disc < 0) return null;
        double sq = Math.sqrt(disc);
        double t1 = (-b - sq) / (2 * a), t2 = (-b + sq) / (2 * a);
        double t = t1 >= 0 && t1 <= 1 ? t1 : (t2 >= 0 && t2 <= 1 ? t2 : -1);
        return t < 0 ? null : p0.add(d.scale(t));
    }

    private static void intercept(ServerWorld w, Shell sh, Entity e, Vector3d at) {
        if (!e.isAlive() || sh.collapsed) return;
        // give back what can be picked up (as the item it would have dropped), never more
        if (e instanceof AbstractArrowEntity) {
            AbstractArrowEntity arrow = (AbstractArrowEntity) e;
            if (arrow.pickup == AbstractArrowEntity.PickupStatus.ALLOWED) {
                ItemStack stack = ((AbstractArrowEntityAccess) arrow).ecmoons$getPickupItem();
                if (stack != null && !stack.isEmpty()) {
                    Vector3d out = at.subtract(sh.center).normalize().scale(0.3).add(at); // just on the side it came from
                    if (sh.inside(e.position())) out = at.subtract(at.subtract(sh.center).normalize().scale(0.3));
                    ItemEntity item = new ItemEntity(w, out.x, out.y, out.z, stack.copy());
                    item.setDeltaMovement(Vector3d.ZERO);
                    w.addFreshEntity(item);
                }
            }
        }
        e.remove();
        intercepted++;
        w.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 4, 0.1, 0.1, 0.1, 0.02);
        w.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BLOCK, SoundCategory.PLAYERS, 0.5f, 1.3f);
        sh.durability -= explosive(e) ? s().i("shell.explosiveCost") : s().i("shell.projectileCost");
        Network.CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(at.x, at.y, at.z, 64, sh.dim)),
                new AbilityFxPacket(AbilityFxPacket.SHELL_HIT, sh.id, at.x, at.y, at.z, 0f, (float) sh.radius, 0,
                        Math.max(0, sh.durability) / (float) Math.max(1, s().i("shell.durability"))));
        ServerPlayerEntity owner = w.getServer().getPlayerList().getPlayer(sh.owner);
        if (owner != null) Network.send(owner, new AbilityStatePacket((byte) 2, 2, (int) Math.max(0, sh.expires - MoonAbilities.now()),
                Math.max(0, sh.durability), s().i("shell.durability")));
        if (sh.durability <= 0) collapse(sh, "broken");
    }

    /** Safety net: an impact on the other side of a shell from where the projectile was is cancelled. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onImpact(ProjectileImpactEvent event) {
        Entity e = event.getEntity();
        if (SHELLS.isEmpty() || e.level.isClientSide || !(e.level instanceof ServerWorld)) return;
        RayTraceResult hit = event.getRayTraceResult();
        if (hit == null || hit.getType() == RayTraceResult.Type.MISS) return;
        for (Shell sh : SHELLS) {
            if (sh.collapsed || !sh.dim.equals(e.level.dimension())) continue;
            Boolean was = SIDE.get(key(sh, e));
            boolean from = was != null ? was : sh.inside(e.position());
            if (from != sh.inside(hit.getLocation())) {
                event.setCanceled(true);
                Vector3d at = crossing(sh, e.position(), hit.getLocation());
                intercept((ServerWorld) e.level, sh, e, at != null ? at : e.position());
                return;
            }
        }
    }

    /** Players inside an active shell take less explosion damage (once, even where shells overlap). */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(LivingHurtEvent event) {
        if (SHELLS.isEmpty() || !event.getSource().isExplosion() || !(event.getEntityLiving() instanceof PlayerEntity)) return;
        Entity p = event.getEntityLiving();
        for (Shell sh : SHELLS) {
            if (!sh.collapsed && sh.dim.equals(p.level.dimension()) && sh.inside(p.position().add(0, 0.9, 0))) {
                event.setAmount(event.getAmount() * (float) (1 - s().d("shell.explosionReduction")));
                return;
            }
        }
    }

    // ---- admin / tests -----------------------------------------------------------------------------------------------

    public static List<String> describe(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        long now = MoonAbilities.now();
        for (Shell sh : SHELLS) {
            if (sh.collapsed) continue;
            ServerPlayerEntity p = server.getPlayerList().getPlayer(sh.owner);
            out.add("#" + sh.id + " by " + (p != null ? p.getScoreboardName() : sh.owner) + " in " + sh.dim.location() + " at "
                    + (int) Math.floor(sh.center.x) + " " + (int) Math.floor(sh.center.y) + " " + (int) Math.floor(sh.center.z)
                    + ", " + (sh.expires - now) / 20 + "s left, durability " + sh.durability);
        }
        for (UUID id : CHARGING.keySet()) {
            ServerPlayerEntity p = server.getPlayerList().getPlayer(id);
            out.add("charging: " + (p != null ? p.getScoreboardName() : id));
        }
        return out;
    }

    /** Tests: a shell without an owner checks (no channel), collapsed by time only. */
    public static int spawnForTest(ServerWorld w, Vector3d center, double radius, int ticks, int durability, UUID owner) {
        Shell sh = new Shell(nextId++, owner, w.dimension(), center, center, radius, MoonAbilities.now() + ticks, durability);
        SHELLS.add(sh);
        TEST_SHELLS.add(sh.id);
        return sh.id;
    }

    static final java.util.Set<Integer> TEST_SHELLS = new java.util.HashSet<>();

    public static boolean active(int id) {
        for (Shell sh : SHELLS) if (sh.id == id && !sh.collapsed) return true;
        return false;
    }

    public static int durability(int id) {
        for (Shell sh : SHELLS) if (sh.id == id) return sh.durability;
        return -1;
    }

    public static int activeCount() {
        int n = 0;
        for (Shell sh : SHELLS) if (!sh.collapsed) n++;
        return n;
    }

    public static boolean charging(PlayerEntity p) {
        return CHARGING.containsKey(p.getUUID());
    }

    public static boolean hasShell(PlayerEntity p) {
        return owned(p) != null;
    }
}
