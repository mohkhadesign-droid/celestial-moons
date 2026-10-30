package net.ecmoons.feature.thread;

import net.ecmoons.config.Settings;
import net.ecmoons.net.Network;
import net.ecmoons.net.ThreadReactionPacket;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import java.util.HashMap;
import java.util.Map;

/**
 * The moon cat's reactions: ONE server-chosen cosmetic stream per dimension, so everyone looking at the same sky sees
 * the same animation. A reaction packet carries only its type, the event id, a sequence number and the game time it
 * starts - never who did what, where, or which team.
 * <p>
 * Rules: ordinary reactions (Lifeline, Guard Thread, Knotbind) at most one per ordinaryCooldownSeconds in the
 * dimension and never over another reaction; at most ONE pending ordinary reaction (higher priority replaces it,
 * duplicates coalesce) which is dropped once older than maxPendingSeconds - stale combat is never replayed. Last
 * Stitch outranks everything: it replaces a pending reaction and takes over at the next short pose boundary (at most
 * 5 ticks later); its large sky accent is limited to one per lastStitchCooldownSeconds, and simultaneous rescues merge
 * into one. Gameplay and the players' own feedback never wait for any of this.
 */
public final class ThreadReactions {
    public static final byte LIFELINE = 1, GUARD = 2, KNOT = 3, LAST_STITCH = 4;
    /** Reaction lengths in ticks (the client animations last this long: 80 ms frames). */
    public static int duration(byte type) {
        switch (type) {
            case LIFELINE: return 24;  // 15 frames
            case GUARD: return 21;     // 13 frames
            case KNOT: return 34;      // 21 frames
            case LAST_STITCH: return 47; // 29 frames
            default: return 0;
        }
    }

    static final class Stream {
        long busyUntil = -1, lastOrdinary = -100000, lastBig = -100000;
        byte pendingType;
        long pendingAt;
        int seq;
        byte playing;
    }

    private static final Map<RegistryKey<World>, Stream> STREAMS = new HashMap<>();
    /** Reactions actually broadcast (tests). */
    public static final Map<Byte, Integer> SENT = new HashMap<>();

    private ThreadReactions() {}

    /** Ask for a reaction (call only after the gameplay event really happened). Server tick time. */
    public static void request(ServerWorld w, byte type) {
        request(w, type, w.getGameTime());
    }

    /** The same with an explicit game time (tests drive the clock). */
    public static void request(ServerWorld w, byte type, long now) {
        Stream s = STREAMS.computeIfAbsent(w.dimension(), k -> new Stream());
        Settings c = Settings.server();
        if (type == LAST_STITCH) {
            if (now - s.lastBig < c.d("thread_reactions.lastStitchCooldownSeconds") * 20) return; // merged into the last one
            s.lastBig = now;
            s.pendingType = 0;
            long start = s.busyUntil > now ? Math.min(s.busyUntil, now + 5) : now; // next safe pose boundary
            start(w, s, type, start);
            return;
        }
        if (s.pendingType != 0 && priority(type) < priority(s.pendingType)) return;
        if (s.pendingType == type) return; // coalesce
        s.pendingType = type;
        s.pendingAt = now;
        tick(w, now);
    }

    static int priority(byte t) {
        return t; // LIFELINE < GUARD < KNOT < LAST_STITCH
    }

    /** Every tick per dimension: start or drop the pending reaction. */
    public static void tick(ServerWorld w) {
        tick(w, w.getGameTime());
    }

    public static void tick(ServerWorld w, long now) {
        Stream s = STREAMS.get(w.dimension());
        if (s == null || s.pendingType == 0) return;
        Settings c = Settings.server();
        if (now - s.pendingAt > c.d("thread_reactions.maxPendingSeconds") * 20) {
            s.pendingType = 0; // stale: never replayed
            return;
        }
        if (s.busyUntil > now || now - s.lastOrdinary < c.d("thread_reactions.ordinaryCooldownSeconds") * 20) return;
        byte t = s.pendingType;
        s.pendingType = 0;
        s.lastOrdinary = now;
        start(w, s, t, now);
    }

    private static void start(ServerWorld w, Stream s, byte type, long start) {
        s.seq++;
        s.playing = type;
        s.busyUntil = start + duration(type);
        SENT.merge(type, 1, Integer::sum);
        ThreadReactionPacket p = new ThreadReactionPacket(type, ThreadEvent.get(w).id().hashCode(), s.seq, start);
        for (ServerPlayerEntity pl : w.players()) Network.send(pl, p);
    }

    /** A player arrived: the reaction currently playing (if any), so their moon matches everyone else's. */
    public static void sync(ServerPlayerEntity p) {
        Stream s = STREAMS.get(p.level.dimension());
        if (s == null || s.busyUntil <= p.level.getGameTime() || s.playing == 0) return;
        Network.send(p, new ThreadReactionPacket(s.playing, ThreadEvent.get(p.getLevel()).id().hashCode(), s.seq,
                s.busyUntil - duration(s.playing)));
    }

    public static void end(ServerWorld w) {
        STREAMS.remove(w.dimension());
    }

    public static void clear() {
        STREAMS.clear();
        SENT.clear();
    }

    /** Tests: the last reaction started in a dimension: {type, seq, start}. */
    public static long[] last(ServerWorld w) {
        Stream s = STREAMS.get(w.dimension());
        return s == null ? new long[]{0, 0, 0} : new long[]{s.playing, s.seq, s.busyUntil - duration(s.playing)};
    }

    /** Tests: the pending reaction type in a dimension (0 = none). */
    public static byte pending(ServerWorld w) {
        Stream s = STREAMS.get(w.dimension());
        return s == null ? 0 : s.pendingType;
    }
}
