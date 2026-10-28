package net.ecmoons.feature;

import net.ecmoons.MoonType;
import net.ecmoons.config.Matchers;
import net.ecmoons.config.Settings;
import net.ecmoons.net.FrostStatePacket;
import net.ecmoons.net.Network;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.state.BooleanProperty;
import net.minecraft.state.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Frost Moon: open-sky exposure slows players with a dedicated attribute modifier (never a potion, never other
 * modifiers). Shelter (any motion-blocking block above the head), a nearby configured heat source or the warmth
 * from eating clears the penalty. Insulating armour delays it or prevents it.
 */
public final class FrostFeatures {
    public static final UUID SLOW_MODIFIER = UUID.fromString("0f4e6a0b-1c55-4b8e-9d0a-6c2f61a0f3d7");
    public static final int CHECK_INTERVAL = 10;

    private static final Map<UUID, State> STATES = new HashMap<>();

    private FrostFeatures() {}

    public static final class State {
        int exposureTicks;
        long warmUntil;
        boolean chilled;
        byte lastSent = -1;
        int lastSentProgressBucket = -1;

        public int exposureTicks() {
            return exposureTicks;
        }

        public boolean chilled() {
            return chilled;
        }

        public long warmUntil() {
            return warmUntil;
        }
    }

    public static State state(ServerPlayerEntity player) {
        return STATES.computeIfAbsent(player.getUUID(), u -> new State());
    }

    /** Eating refreshes (never stacks) the warmth timer. Called from food-finish events at any time. */
    public static void onAte(ServerPlayerEntity player) {
        State st = state(player);
        st.warmUntil = player.level.getGameTime() + 20L * Settings.server().i("frost.warmthSeconds");
    }

    /** Runs every CHECK_INTERVAL ticks per player. */
    public static void tick(ServerPlayerEntity player, MoonType active) {
        State st = state(player);
        if (active != MoonType.FROST || player.isCreative() || player.isSpectator()) {
            clear(player, st);
            return;
        }
        Settings s = Settings.server();
        long now = player.level.getGameTime();
        int pieces = insulatingPieces(player, s);
        boolean immune = s.b("frost.fullSetImmune") && pieces >= 4;
        byte status;
        if (immune) {
            st.exposureTicks = 0;
            status = FrostStatePacket.SHELTERED;
        } else if (st.warmUntil > now) {
            st.exposureTicks = 0;
            status = FrostStatePacket.WARM;
        } else if (!isExposed(player) || nearHeat(player, s)) {
            st.exposureTicks = 0;
            status = FrostStatePacket.SHELTERED;
        } else {
            st.exposureTicks += CHECK_INTERVAL;
            status = FrostStatePacket.EXPOSED;
        }
        int delay = 20 * (s.i("frost.exposureDelaySeconds") + pieces * s.i("frost.delayBonusPerPieceSeconds"));
        boolean chilled = !immune && st.exposureTicks >= delay;
        if (chilled) status = FrostStatePacket.CHILLED;
        applySlowdown(player, chilled, s.d("frost.slowdown"));
        st.chilled = chilled;
        float progress = delay <= 0 ? 1f : Math.min(1f, st.exposureTicks / (float) delay);
        int bucket = (int) (progress * 10);
        if (status != st.lastSent || bucket != st.lastSentProgressBucket) {
            st.lastSent = status;
            st.lastSentProgressBucket = bucket;
            Network.send(player, new FrostStatePacket(status, progress));
        }
    }

    /** Exposed when the head is at or above the highest motion-blocking block (glass and leaves are shelter). */
    public static boolean isExposed(ServerPlayerEntity player) {
        World world = player.level;
        BlockPos head = new BlockPos(player.getX(), player.getEyeY(), player.getZ());
        return head.getY() >= world.getHeight(Heightmap.Type.MOTION_BLOCKING, head.getX(), head.getZ());
    }

    public static boolean nearHeat(ServerPlayerEntity player, Settings s) {
        int r = s.i("frost.heatSourceRadius");
        if (r <= 0) return false;
        boolean requireLit = s.b("frost.requireLitState");
        World world = player.level;
        BlockPos center = player.blockPosition();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = world.getBlockState(m);
                    if (state.isAir()) continue;
                    if (!Matchers.block(s, "frost.heatSources", state.getBlock())) continue;
                    if (requireLit && !isLit(state)) continue;
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isLit(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if (p instanceof BooleanProperty && "lit".equals(p.getName())) return state.getValue((BooleanProperty) p);
        }
        return true; // blocks without a lit property (fire, lava, magma) are always hot
    }

    public static int insulatingPieces(ServerPlayerEntity player, Settings s) {
        int n = 0;
        for (ItemStack stack : player.getArmorSlots()) {
            if (!stack.isEmpty() && Matchers.item(s, "frost.insulatingItems", stack.getItem())) n++;
        }
        return n;
    }

    private static void applySlowdown(ServerPlayerEntity player, boolean on, double amount) {
        ModifiableAttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        AttributeModifier existing = speed.getModifier(SLOW_MODIFIER);
        if (on) {
            if (existing != null && existing.getAmount() == -amount) return;
            if (existing != null) speed.removeModifier(SLOW_MODIFIER);
            speed.addTransientModifier(new AttributeModifier(SLOW_MODIFIER, "ecmoons frost exposure", -amount,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
        } else if (existing != null) {
            speed.removeModifier(SLOW_MODIFIER);
        }
    }

    /** Remove everything the Frost Moon owns from this player (moon ended, dimension change, logout...). */
    public static void clear(ServerPlayerEntity player, State st) {
        applySlowdown(player, false, 0);
        st.exposureTicks = 0;
        st.chilled = false;
        if (st.lastSent != FrostStatePacket.NONE) {
            st.lastSent = FrostStatePacket.NONE;
            st.lastSentProgressBucket = -1;
            Network.send(player, new FrostStatePacket(FrostStatePacket.NONE, 0f));
        }
    }

    public static void clear(ServerPlayerEntity player) {
        clear(player, state(player));
    }

    public static void forget(UUID player) {
        STATES.remove(player);
    }
}
