package net.ecmoons.feature.thread;

import net.ecmoons.feature.ThreadFeatures;
import net.ecmoons.net.KnotPacket;
import net.ecmoons.net.Network;
import net.ecmoons.net.ThreadFxPacket;
import net.ecmoons.net.ThreadPacket;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who receives what. Friendly support state (links, guard pairs, Lifeline/Guard/Last Stitch effects) is built from
 * and sent to the members of ONE team only - the server never broadcasts it. Knotbind tethers are a hostile, public
 * interaction: victims, caster and players within knotViewRange see them. Everything is sent on change, at most at
 * the connection interval, with a content hash so nothing is resent needlessly.
 */
public final class ThreadNet {
    public static final byte FX_LIFELINE = 1, FX_GUARD = 2, FX_LAST_STITCH = 3, FX_KNOT_RELEASE = 4;
    private static final Map<UUID, Integer> SENT = new HashMap<>(), SENT_KNOTS = new HashMap<>();
    /** Last packet built per player (tests: fake players have no connection). */
    public static final Map<UUID, ThreadPacket> LAST = new HashMap<>();
    public static final Map<UUID, int[]> LAST_KNOTS = new HashMap<>();
    public static final List<String> FX_LOG = new ArrayList<>();

    private ThreadNet() {}

    /** Build and (if changed) send every player's own state in this world. */
    public static void sendStates(ServerWorld w, long now, boolean up) {
        for (ServerPlayerEntity p : w.players()) send(p, build(p, now, up));
    }

    static void send(ServerPlayerEntity p, ThreadPacket pk) {
        int h = pk.hash();
        Integer last = SENT.get(p.getUUID());
        if (last == null ? pk.empty() : last == h) return;
        LAST.put(p.getUUID(), pk);
        Network.send(p, pk);
        if (pk.empty()) SENT.remove(p.getUUID());
        else SENT.put(p.getUUID(), h);
    }

    public static ThreadPacket build(ServerPlayerEntity p, long now, boolean up) {
        ThreadPacket pk = new ThreadPacket();
        if (!up) return pk;
        String team = ThreadLinks.team(p);
        pk.reduction = ThreadFeatures.reduction(p);
        if (team != null) {
            pk.links = ThreadFeatures.links(p.getLevel(), team);
            List<Integer> g = new ArrayList<>();
            for (Map.Entry<UUID, GuardThread.Guard> e : GuardThread.all().entrySet()) {
                ServerPlayerEntity gp = p.server.getPlayerList().getPlayer(e.getKey());
                if (gp == null || gp.level != p.level || !team.equals(ThreadLinks.team(gp))) continue;
                g.add(gp.getId());
                g.add(e.getValue().targetId);
                g.add(e.getValue().since >= 0 ? 1 : 0);
            }
            pk.guards = g.stream().mapToInt(Integer::intValue).toArray();
            pk.lastStitch = (byte) (ThreadEvent.get(p.getLevel()).spent(p.getUUID()) ? 2 : 1);
        }
        GuardThread.Guard mine = GuardThread.guardOf(p);
        if (mine != null) {
            ServerPlayerEntity t = Lifeline.player(p, mine.targetId);
            pk.guardTarget = t == null ? "?" : t.getScoreboardName();
            pk.guardActive = mine.since >= 0;
            pk.guardEstablished = GuardThread.established(p, now);
        }
        ServerPlayerEntity by = GuardThread.guardianOf(p);
        if (by != null) pk.guardedBy = by.getScoreboardName();
        float[] ch = Lifeline.channelOf(p, now);
        if (ch != null) {
            pk.lifelineTarget = (int) ch[0];
            pk.lifelineAim = ch[1];
            pk.lifelineMoved = ch[2];
        }
        pk.lifelineCooldown = Lifeline.cooldown(p, now);
        pk.guardCooldown = GuardThread.cooldown(p, now);
        pk.knotCooldown = Knotbind.cooldown(p, now);
        Knotbind.Mark m = Knotbind.markOf(p);
        if (m != null && m.until > now) {
            pk.markTarget = m.first.getId();
            pk.markTicks = (int) (m.until - now);
        }
        pk.taggedTicks = Knotbind.taggedFor(p, now);
        Knotbind.Knot k = Knotbind.knotOf(p);
        pk.unravel = k == null ? -1f : k.progress(p, now);
        pk.knotTicks = k == null ? 0 : (int) Math.max(0, k.end - now);
        return pk;
    }

    /** Resend this player's state at the next interval. */
    public static void dirty(ServerPlayerEntity p) {
        SENT.put(p.getUUID(), 0);
    }

    /** A friendly effect: only the owner's team in that world receives it. */
    public static void fxTeam(ServerPlayerEntity owner, byte kind, int a, int b, float value) {
        String team = ThreadLinks.team(owner);
        FX_LOG.add(kind + ":" + a + ">" + b + ":" + value);
        if (FX_LOG.size() > 64) FX_LOG.remove(0);
        if (team == null) return;
        ThreadFxPacket pk = new ThreadFxPacket(kind, a, b, value);
        for (ServerPlayerEntity p : owner.getLevel().players()) {
            if (team.equals(ThreadLinks.team(p))) Network.send(p, pk);
        }
    }

    public static void notice(ServerPlayerEntity p, String msg) {
        if (msg == null || msg.isEmpty()) return;
        p.displayClientMessage(new StringTextComponent(msg).withStyle(TextFormatting.LIGHT_PURPLE), true);
    }

    static boolean sees(ServerPlayerEntity p, Knotbind.Knot k) {
        if (k.a == p || k.b == p || k.caster.equals(p.getUUID())) return true;
        double r = net.ecmoons.config.Settings.server().d("thread_knotbind.knotViewRange");
        return p.distanceToSqr(k.a) <= r * r || p.distanceToSqr(k.b) <= r * r;
    }

    /** Knot list for each player who can see one (and an empty list once, to clear). */
    public static void knotsChanged(ServerWorld w) {
        long now = Knotbind.now();
        for (ServerPlayerEntity p : w.players()) {
            List<Integer> d = new ArrayList<>();
            for (Knotbind.Knot k : Knotbind.knots()) {
                if (k.a.level != w || !sees(p, k)) continue;
                d.add(k.id);
                d.add(k.a.getId());
                d.add(k.b.getId());
                d.add((int) Math.max(0, k.end - now));
                d.add(Math.round(k.progress(k.a, now) * 100));
                d.add(Math.round(k.progress(k.b, now) * 100));
            }
            int[] arr = d.stream().mapToInt(Integer::intValue).toArray();
            int h = Arrays.hashCode(arr);
            Integer last = SENT_KNOTS.get(p.getUUID());
            if (last == null ? arr.length == 0 : last == h) continue;
            LAST_KNOTS.put(p.getUUID(), arr);
            Network.send(p, new KnotPacket(arr));
            if (arr.length == 0) SENT_KNOTS.remove(p.getUUID());
            else SENT_KNOTS.put(p.getUUID(), h);
        }
    }

    /** Loose fibres where a knot came apart: everyone who could see it. */
    static void knotReleased(ServerWorld w, Knotbind.Knot k) {
        ThreadFxPacket pk = new ThreadFxPacket(FX_KNOT_RELEASE, k.a.getId(), k.b.getId(), 0f);
        FX_LOG.add(FX_KNOT_RELEASE + ":" + k.a.getId() + ">" + k.b.getId());
        for (ServerPlayerEntity p : w.players()) if (sees(p, k)) Network.send(p, pk);
    }

    public static void forget(UUID id) {
        SENT.remove(id);
        SENT_KNOTS.remove(id);
        LAST.remove(id);
        LAST_KNOTS.remove(id);
    }

    public static void clear() {
        SENT.clear();
        SENT_KNOTS.clear();
        LAST.clear();
        LAST_KNOTS.clear();
        FX_LOG.clear();
    }

}
