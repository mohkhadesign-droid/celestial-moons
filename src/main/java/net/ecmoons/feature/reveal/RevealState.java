package net.ecmoons.feature.reveal;

import net.ecmoons.config.Settings;
import net.ecmoons.feature.RevealingFeatures;
import net.ecmoons.net.Network;
import net.ecmoons.net.RevealPacket;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.Effects;
import net.minecraft.util.RegistryKey;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Each revealed player's own reveal timer (server-owned, in server ticks) and True Exposure.
 * <p>
 * A player is revealed at a scan's completion for revealSeconds (0 = markerSeconds). While revealed they are
 * "exposed": vanilla Invisibility no longer hides them. The potion is never removed or shortened - only the
 * synchronised invisible flag that vanilla derives from it is held off (see LivingEntityExposureMixin, which
 * re-applies this after every vanilla recalculation, so drinking a potion while exposed does not hide anyone).
 * When the reveal ends the flag is set again only if the real Invisibility effect still has time left; an expired
 * or milk-cleared effect is never restored, and invisibility that another system set without the potion is never
 * touched (the flag is only changed while the potion is present).
 * <p>
 * Nothing here is saved: logging out, dying, changing dimension, becoming ineligible or the moon ending all end
 * the reveal through {@link #end} - the same path as a normal expiry.
 */
public final class RevealState {
    public static final class Entry {
        public final int scanId;
        public final long start, end;
        public final RegistryKey<World> dimension;
        boolean exposed;

        Entry(int scanId, long start, long end, RegistryKey<World> dimension) {
            this.scanId = scanId;
            this.start = start;
            this.end = end;
            this.dimension = dimension;
        }

        public int total() {
            return (int) (end - start);
        }
    }

    /** Why a reveal ended (logged by tests and the status command). */
    public enum EndReason { EXPIRED, DEATH, DIMENSION, LOGOUT, INELIGIBLE, MOON_ENDED, REFRESHED, ADMIN }

    private static final Map<UUID, Entry> REVEALED = new HashMap<>();
    /** Last end reason per player (diagnostics / tests). */
    public static final Map<UUID, EndReason> LAST_END = new HashMap<>();

    private RevealState() {}

    public static int durationTicks() {
        Settings s = Settings.server();
        int secs = s.i("revealing.revealSeconds");
        return Math.max(1, (secs > 0 ? secs : s.i("revealing.markerSeconds")) * 20);
    }

    /** Start (or refresh with the authoritative new timer - never stacking) this player's reveal. */
    public static void reveal(ServerPlayerEntity p, int scanId, long now, int ticks) {
        Entry old = REVEALED.get(p.getUUID());
        Entry e = new Entry(scanId, now, now + ticks, p.level.dimension());
        REVEALED.put(p.getUUID(), e);
        if (Settings.server().b("revealing_exposure.enabled")) expose(p, e);
        else if (old != null && old.exposed) unexpose(p);
        Network.send(p, RevealPacket.revealed(scanId, ticks, ticks));
    }

    @Nullable
    public static Entry get(ServerPlayerEntity p) {
        return REVEALED.get(p.getUUID());
    }

    public static boolean isRevealed(ServerPlayerEntity p) {
        return REVEALED.containsKey(p.getUUID());
    }

    public static int remaining(ServerPlayerEntity p, long now) {
        Entry e = REVEALED.get(p.getUUID());
        return e == null ? 0 : (int) Math.max(0, e.end - now);
    }

    public static int count() {
        return REVEALED.size();
    }

    /** Per tick for every player of a world: expiry, eligibility and dimension checks. */
    public static void tick(List<ServerPlayerEntity> players, long now) {
        for (ServerPlayerEntity p : players) {
            Entry e = REVEALED.get(p.getUUID());
            if (e == null) continue;
            if (!p.level.dimension().equals(e.dimension)) end(p, EndReason.DIMENSION);
            else if (now >= e.end) end(p, EndReason.EXPIRED);
            else if (RevealingFeatures.hidden(p)) end(p, p.isAlive() ? EndReason.INELIGIBLE : EndReason.DEATH);
        }
    }

    /** End this player's reveal: restore concealment correctly, release the gaze bind, clear the panel and the client. */
    public static void end(ServerPlayerEntity p, EndReason reason) {
        Entry e = REVEALED.remove(p.getUUID());
        BindingGaze.release(p, false);
        ReadYourEnemy.forget(p, true);
        if (reason != EndReason.EXPIRED && reason != EndReason.REFRESHED) WorldNotices.forgetPlayer(p.getUUID());
        if (e == null) return;
        LAST_END.put(p.getUUID(), reason);
        if (e.exposed) unexpose(p);
        Network.send(p, RevealPacket.revealed(e.scanId, 0, e.total()));
    }

    /** Drop the state of a player who is gone (logout) without touching the entity further than restoring the flag. */
    public static void forget(ServerPlayerEntity p) {
        end(p, EndReason.LOGOUT);
        LAST_END.remove(p.getUUID());
    }

    /** End every reveal in this dimension (moon ended / cancelled / admin stop). */
    public static void endAll(List<ServerPlayerEntity> players, RegistryKey<World> dimension, EndReason reason) {
        for (ServerPlayerEntity p : new ArrayList<>(players)) {
            Entry e = REVEALED.get(p.getUUID());
            if (e != null && e.dimension.equals(dimension)) end(p, reason);
        }
        BindingGaze.releaseAll(players);
    }

    public static void clear() {
        REVEALED.clear();
        LAST_END.clear();
    }

    // ---- True Exposure ------------------------------------------------------------------------------------------

    private static void expose(ServerPlayerEntity p, Entry e) {
        e.exposed = true;
        if (p.hasEffect(Effects.INVISIBILITY) && p.isInvisible()) p.setInvisible(false);
    }

    private static void unexpose(ServerPlayerEntity p) {
        EffectInstance inv = p.getEffect(Effects.INVISIBILITY);
        if (inv != null && inv.getDuration() > 0 && !p.isInvisible()) p.setInvisible(true);
    }

    /** True while vanilla Invisibility must not hide this entity (called from the invisibility recalculation). */
    public static boolean exposed(LivingEntity entity) {
        if (!(entity instanceof ServerPlayerEntity) || REVEALED.isEmpty()) return false;
        Entry e = REVEALED.get(entity.getUUID());
        return e != null && e.exposed;
    }

    /** Hook body for LivingEntityExposureMixin: after vanilla recomputed the flag, hold potion invisibility off. */
    public static void afterInvisibilityUpdate(LivingEntity entity) {
        if (entity.level.isClientSide || !exposed(entity)) return;
        if (entity.hasEffect(Effects.INVISIBILITY) && entity.isInvisible()) entity.setInvisible(false);
    }
}
