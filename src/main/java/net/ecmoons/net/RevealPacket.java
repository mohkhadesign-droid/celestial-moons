package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> client: Revealing Moon. The server owns every phase and timer; clients only animate and draw.
 * Times are the dimension's game time (identical on every client of that dimension), so all players see the same
 * eye and the same ripple progress, and a late packet catches up instead of starting late.
 * <ul>
 *   <li>WARN: scan {@code scanId} began its warning at {@code time}; the eye opens for {@code a} ticks, then the
 *       active (fully open) scan runs until completion at time + {@code b}.</li>
 *   <li>SCAN: scan completion at {@code time}: the ripple starts, the eye begins to close, markers show for
 *       {@code ticks}.</li>
 *   <li>BLINK: one blink (preview). STOP: the moon ended - clear everything.</li>
 *   <li>SYNC: joining/changing dimension mid-moon: the current cycle ({@code time} = warning start or -1, a, b)
 *       and the last completion ({@code time2}, -1 = none) so a running ripple is caught up, an old one never replayed.</li>
 *   <li>REVEALED: your own reveal: {@code a} ticks remaining of {@code b} total (a = 0 ends it).</li>
 *   <li>BIND: Binding Gaze started (a = 1) or released (a = 0).</li>
 * </ul>
 */
public class RevealPacket {
    public static final byte WARN = 0, SCAN = 1, BLINK = 2, STOP = 3, SYNC = 4, REVEALED = 5, BIND = 6;

    public final byte stage;
    public final int ticks;
    public final List<Vector3d> markers;
    public final int scanId, a, b;
    public final long time, time2;

    public RevealPacket(byte stage, int ticks, List<Vector3d> markers) {
        this(stage, ticks, markers, -1, -1L, -1L, 0, 0);
    }

    public RevealPacket(byte stage, int ticks, List<Vector3d> markers, int scanId, long time, long time2, int a, int b) {
        this.stage = stage;
        this.ticks = ticks;
        this.markers = markers;
        this.scanId = scanId;
        this.time = time;
        this.time2 = time2;
        this.a = a;
        this.b = b;
    }

    public static RevealPacket warn(int scanId, long warnTime, int openingTicks, int countdownTicks) {
        return new RevealPacket(WARN, countdownTicks, Collections.emptyList(), scanId, warnTime, -1L, openingTicks, countdownTicks);
    }

    public static RevealPacket scan(int scanId, long completeTime, int markerTicks, List<Vector3d> markers) {
        return new RevealPacket(SCAN, markerTicks, markers, scanId, completeTime, -1L, 0, 0);
    }

    public static RevealPacket sync(int scanId, long warnTime, int openingTicks, int countdownTicks, long lastComplete) {
        return new RevealPacket(SYNC, 0, Collections.emptyList(), scanId, warnTime, lastComplete, openingTicks, countdownTicks);
    }

    public static RevealPacket revealed(int scanId, int remaining, int total) {
        return new RevealPacket(REVEALED, 0, Collections.emptyList(), scanId, -1L, -1L, remaining, total);
    }

    public static RevealPacket bind(boolean bound, int scanId) {
        return new RevealPacket(BIND, 0, Collections.emptyList(), scanId, -1L, -1L, bound ? 1 : 0, 0);
    }

    public static void encode(RevealPacket p, PacketBuffer buf) {
        buf.writeByte(p.stage);
        buf.writeVarInt(p.ticks);
        buf.writeVarInt(p.scanId + 1);
        buf.writeLong(p.time);
        buf.writeLong(p.time2);
        buf.writeVarInt(p.a);
        buf.writeVarInt(p.b);
        buf.writeVarInt(p.markers.size());
        for (Vector3d v : p.markers) {
            buf.writeFloat((float) v.x);
            buf.writeFloat((float) v.y);
            buf.writeFloat((float) v.z);
        }
    }

    public static RevealPacket decode(PacketBuffer buf) {
        byte stage = buf.readByte();
        int ticks = buf.readVarInt();
        int scanId = buf.readVarInt() - 1;
        long time = buf.readLong(), time2 = buf.readLong();
        int a = buf.readVarInt(), b = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), 1024);
        List<Vector3d> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(new Vector3d(buf.readFloat(), buf.readFloat(), buf.readFloat()));
        return new RevealPacket(stage, ticks, list, scanId, time, time2, a, b);
    }

    public static void handle(RevealPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.RevealClient.onPacket(p)));
        ctx.get().setPacketHandled(true);
    }
}
