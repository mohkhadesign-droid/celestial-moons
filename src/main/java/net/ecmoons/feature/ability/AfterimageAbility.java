package net.ecmoons.feature.ability;

import net.ecmoons.MoonType;
import net.ecmoons.config.Settings;
import net.ecmoons.net.AbilityFxPacket;
import net.ecmoons.net.AbilityStatePacket;
import net.ecmoons.net.Network;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.play.server.SPlayerPositionLookPacket;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Afterimage Moon - Frame Step. All timing is counted in server ticks, so the client's frame rate cannot change
 * distances or windows.
 * <ol>
 *   <li>The first press performs step 1 (2 blocks by default) in the direction the player is moving (or looking).</li>
 *   <li>When a step ends, the next press must arrive within the input window (5 ticks = 0.25 s). It performs the
 *       next, longer step; its direction may differ from the previous step by at most maxSteerDegrees and never
 *       changes during a step.</li>
 *   <li>A press during a step or before the window (too early) ends the sequence with a short slowdown. Not
 *       pressing in time simply ends the sequence.</li>
 *   <li>After a successful third step, the next valid melee hit within finalWindowTicks gets extra knockback.</li>
 * </ol>
 * Steps move the player a little each tick and stop before any collision (walls, closed doors, ceilings, barriers,
 * the world border); nothing passes through blocks. No flight, invulnerability or fall-damage changes.
 * Each step leaves a purely visual afterimage (a client-side drawing, not an entity) for nearby players.
 */
public final class AfterimageAbility {
    private static final UUID PENALTY = UUID.fromString("0d9f3c52-3b1e-4a9a-8f45-5c2f7b1e9a61");

    private static final class Seq {
        int stage;          // steps performed so far (1..3)
        int stepTicksLeft;  // > 0 while moving
        double perTick;
        Vector3d dir = Vector3d.ZERO, from = Vector3d.ZERO;
        long windowOpen = -1, windowClose = -1;
    }

    private static final Map<UUID, Seq> SEQ = new HashMap<>();
    private static final Map<UUID, Long> BONUS_UNTIL = new HashMap<>();
    private static final Map<UUID, Long> PENALTY_UNTIL = new HashMap<>();
    /** victim entity id -> knockback multiplier, valid for the tick of the qualifying hit. */
    private static final Map<Integer, double[]> PENDING_KNOCKBACK = new HashMap<>();

    public static int bonusHits; // diagnostics / tests
    public static float lastKnockback;
    /** Horizontal distance actually covered by the last step 1, 2 and 3 (index 1..3). */
    public static final double[] LAST_STEPS = new double[4];

    private AfterimageAbility() {}

    private static Settings s() {
        return Settings.server();
    }

    static void onPress(ServerPlayerEntity p, float dirX, float dirZ) {
        long now = MoonAbilities.now();
        Seq seq = SEQ.get(p.getUUID());
        if (seq == null) {
            String why = MoonAbilities.blocked(p, MoonType.AFTERIMAGE);
            if (why == null) why = movementBlocked(p);
            if (why != null) {
                MoonAbilities.fail(p, why);
                return;
            }
            int cd = MoonAbilities.cooldownLeft(p, MoonType.AFTERIMAGE);
            if (cd > 0) {
                MoonAbilities.fail(p, "Frame Step ready in " + (cd + 19) / 20 + "s");
                return;
            }
            seq = new Seq();
            SEQ.put(p.getUUID(), seq);
            startStep(p, seq, 1, desired(p, dirX, dirZ));
            return;
        }
        if (seq.stepTicksLeft > 0 || now < seq.windowOpen) {
            mistimed(p, "Too early");
            return;
        }
        if (now <= seq.windowClose && seq.stage < 3) {
            String why = movementBlocked(p);
            if (why != null) {
                cancel(p, true);
                MoonAbilities.fail(p, why);
                return;
            }
            Vector3d want = desired(p, dirX, dirZ);
            startStep(p, seq, seq.stage + 1, steer(seq.dir, want, s().d("afterimage.maxSteerDegrees")));
        }
    }

    private static String movementBlocked(ServerPlayerEntity p) {
        if (p.isPassenger()) return "Not while riding";
        if (p.isFallFlying() || p.abilities.flying) return "Not while flying";
        if (p.isSwimming() || p.isInWater() && !p.isOnGround()) return "Not while swimming";
        return null;
    }

    /** The intended horizontal direction: the movement keys the client reported, else where the player looks. */
    private static Vector3d desired(ServerPlayerEntity p, float dirX, float dirZ) {
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len > 0.1) return new Vector3d(dirX / len, 0, dirZ / len);
        Vector3d look = p.getLookAngle();
        Vector3d flat = new Vector3d(look.x, 0, look.z);
        return flat.lengthSqr() < 1e-4 ? new Vector3d(0, 0, 1) : flat.normalize();
    }

    /** Turn from {@code from} towards {@code to} by at most maxDeg. */
    static Vector3d steer(Vector3d from, Vector3d to, double maxDeg) {
        double a0 = Math.atan2(from.z, from.x), a1 = Math.atan2(to.z, to.x);
        double d = MathHelper.wrapDegrees(Math.toDegrees(a1 - a0));
        d = MathHelper.clamp(d, -maxDeg, maxDeg);
        double a = a0 + Math.toRadians(d);
        return new Vector3d(Math.cos(a), 0, Math.sin(a));
    }

    private static void startStep(ServerPlayerEntity p, Seq seq, int stage, Vector3d dir) {
        seq.stage = stage;
        seq.dir = dir;
        int ticks = s().i("afterimage.stepTicks");
        seq.stepTicksLeft = ticks;
        seq.perTick = s().d("afterimage.step" + stage + "Distance") / ticks;
        seq.windowOpen = seq.windowClose = -1;
        seq.from = new Vector3d(p.getX(), p.getY(), p.getZ());
        // a purely visual copy where the step started, for the user and everyone nearby
        Network.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> p), new AbilityFxPacket(AbilityFxPacket.GHOST,
                p.getId(), p.getX(), p.getY(), p.getZ(), p.yBodyRot, 0f, s().i("afterimage.afterimageTicks"), stage));
        p.level.playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.util.SoundEvents.PHANTOM_FLAP,
                net.minecraft.util.SoundCategory.PLAYERS, 0.4f, 1.4f + 0.2f * stage);
        sendState(p, seq);
    }

    static void tick() {
        long now = MoonAbilities.now();
        net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        for (Iterator<Map.Entry<UUID, Seq>> it = SEQ.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Seq> e = it.next();
            ServerPlayerEntity p = server.getPlayerList().getPlayer(e.getKey());
            Seq seq = e.getValue();
            if (p == null) {
                it.remove();
                continue;
            }
            if (MoonAbilities.blocked(p, MoonType.AFTERIMAGE) != null || movementBlocked(p) != null) {
                it.remove();
                finish(p);
                continue;
            }
            if (seq.stepTicksLeft > 0) {
                boolean moved = move(p, seq.dir.scale(seq.perTick));
                seq.stepTicksLeft = moved ? seq.stepTicksLeft - 1 : 0; // stop before an obstruction
                if (seq.stepTicksLeft == 0) {
                    LAST_STEPS[seq.stage] = Math.sqrt(Math.pow(p.getX() - seq.from.x, 2) + Math.pow(p.getZ() - seq.from.z, 2));
                    if (seq.stage >= 3) {
                        BONUS_UNTIL.put(p.getUUID(), now + s().i("afterimage.finalWindowTicks"));
                        it.remove();
                        finish(p);
                        sendBonus(p);
                        continue;
                    }
                    seq.windowOpen = now + 1;
                    seq.windowClose = now + s().i("afterimage.inputWindowTicks");
                    sendState(p, seq);
                }
            } else if (seq.windowClose >= 0 && now > seq.windowClose) {
                it.remove(); // the window passed without a press: the sequence just ends
                finish(p);
            }
        }
        for (Iterator<Map.Entry<UUID, Long>> it = PENALTY_UNTIL.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (now < e.getValue()) continue;
            ServerPlayerEntity p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) removePenalty(p);
            it.remove();
        }
        BONUS_UNTIL.values().removeIf(t -> t < now);
        PENDING_KNOCKBACK.clear();
    }

    /** Move by delta, in small sub-steps, stopping before anything solid. Returns false if blocked. */
    private static boolean move(ServerPlayerEntity p, Vector3d delta) {
        int parts = 4;
        Vector3d sub = delta.scale(1.0 / parts);
        AxisAlignedBB bb = p.getBoundingBox();
        Vector3d done = Vector3d.ZERO;
        boolean blocked = false;
        for (int i = 0; i < parts; i++) {
            AxisAlignedBB next = bb.move(done.add(sub));
            if (!p.level.noCollision(p, next) || !p.level.getWorldBorder().isWithinBounds(next)) {
                blocked = true;
                break;
            }
            done = done.add(sub);
        }
        if (done.lengthSqr() > 1e-6) {
            p.connection.teleport(p.getX() + done.x, p.getY() + done.y, p.getZ() + done.z, 0f, 0f,
                    EnumSet.of(SPlayerPositionLookPacket.Flags.X_ROT, SPlayerPositionLookPacket.Flags.Y_ROT));
        }
        return !blocked;
    }

    private static void mistimed(ServerPlayerEntity p, String why) {
        cancel(p, true);
        int ticks = s().i("afterimage.penaltyTicks");
        if (ticks > 0) {
            ModifiableAttributeInstance attr = p.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attr != null && attr.getModifier(PENALTY) == null) {
                attr.addTransientModifier(new AttributeModifier(PENALTY, "ecmoons frame step stumble", -s().d("afterimage.penaltySlowdown"),
                        AttributeModifier.Operation.MULTIPLY_TOTAL));
            }
            PENALTY_UNTIL.put(p.getUUID(), MoonAbilities.now() + ticks);
        }
        MoonAbilities.fail(p, why + " - the sequence breaks");
    }

    private static void removePenalty(ServerPlayerEntity p) {
        ModifiableAttributeInstance attr = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null) attr.removeModifier(PENALTY);
    }

    static void clearPenalty(ServerPlayerEntity p) {
        removePenalty(p);
        PENALTY_UNTIL.remove(p.getUUID());
    }

    /** End the sequence: cooldown starts now. */
    private static void finish(ServerPlayerEntity p) {
        MoonAbilities.startCooldown(p, MoonType.AFTERIMAGE, s().i("afterimage.cooldownSeconds"));
        Network.send(p, new AbilityStatePacket((byte) 1, 0, 0, 0, MoonAbilities.cooldownLeft(p, MoonType.AFTERIMAGE)));
    }

    /** Interrupt (logout, death, dimension change, admin reset, mistiming). */
    static void cancel(ServerPlayerEntity p, boolean withCooldown) {
        boolean had = SEQ.remove(p.getUUID()) != null;
        BONUS_UNTIL.remove(p.getUUID());
        if (!withCooldown) clearPenalty(p);
        if (had && withCooldown) finish(p);
    }

    static void clearAll() {
        SEQ.clear();
        BONUS_UNTIL.clear();
        PENALTY_UNTIL.clear();
        PENDING_KNOCKBACK.clear();
    }

    // ---- HUD sync: kind 1 -> a = steps done, b = 1 stepping / 2 window open / 3 final bonus, c = ticks, d = cooldown --

    private static void sendState(ServerPlayerEntity p, Seq seq) {
        int b = seq.stepTicksLeft > 0 ? 1 : 2;
        int c = seq.stepTicksLeft > 0 ? seq.stepTicksLeft : (int) (seq.windowClose - MoonAbilities.now());
        Network.send(p, new AbilityStatePacket((byte) 1, seq.stage, b, c, 0));
    }

    private static void sendBonus(ServerPlayerEntity p) {
        Network.send(p, new AbilityStatePacket((byte) 1, 3, 3, s().i("afterimage.finalWindowTicks"), MoonAbilities.cooldownLeft(p, MoonType.AFTERIMAGE)));
    }

    // ---- final attack window -----------------------------------------------------------------------------------------

    /** A real melee hit by the player (not a projectile, not blocked by PvP rules - those never reach this event). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHurt(LivingHurtEvent event) {
        DamageSource src = event.getSource();
        Entity attacker = src.getEntity();
        if (!(attacker instanceof ServerPlayerEntity) || src.getDirectEntity() != attacker || src.isProjectile() || event.getAmount() <= 0) return;
        Long until = BONUS_UNTIL.get(attacker.getUUID());
        if (until == null || until < MoonAbilities.now()) return;
        BONUS_UNTIL.remove(attacker.getUUID()); // only the first qualifying hit
        PENDING_KNOCKBACK.put(event.getEntityLiving().getId(), new double[]{1 + s().d("afterimage.knockbackBonus")});
        Network.send((ServerPlayerEntity) attacker, new AbilityStatePacket((byte) 1, 0, 0, 0, MoonAbilities.cooldownLeft((ServerPlayerEntity) attacker, MoonType.AFTERIMAGE)));
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onKnockback(LivingKnockBackEvent event) {
        double[] m = PENDING_KNOCKBACK.remove(event.getEntityLiving().getId());
        if (m != null) {
            event.setStrength((float) (event.getStrength() * m[0]));
            bonusHits++;
        }
        lastKnockback = event.getStrength();
    }

    public static String describe(ServerPlayerEntity p) {
        Seq seq = SEQ.get(p.getUUID());
        Long bonus = BONUS_UNTIL.get(p.getUUID());
        return p.getScoreboardName() + ": " + (seq == null ? "no sequence" : "step " + seq.stage + (seq.stepTicksLeft > 0 ? " (moving)" : " (window)"))
                + (bonus != null ? ", knockback bonus ready" : "") + ", cooldown " + (MoonAbilities.cooldownLeft(p, MoonType.AFTERIMAGE) + 19) / 20 + "s";
    }

    // test hooks
    public static boolean hasBonus(ServerPlayerEntity p) {
        Long b = BONUS_UNTIL.get(p.getUUID());
        return b != null && b >= MoonAbilities.now();
    }

    public static boolean penalised(ServerPlayerEntity p) {
        ModifiableAttributeInstance attr = p.getAttribute(Attributes.MOVEMENT_SPEED);
        return attr != null && attr.getModifier(PENALTY) != null;
    }

    public static int stage(ServerPlayerEntity p) {
        Seq seq = SEQ.get(p.getUUID());
        return seq == null ? 0 : seq.stage;
    }

    public static boolean windowOpen(ServerPlayerEntity p) {
        Seq seq = SEQ.get(p.getUUID());
        long now = MoonAbilities.now();
        return seq != null && seq.stepTicksLeft == 0 && now >= seq.windowOpen && now <= seq.windowClose;
    }
}
