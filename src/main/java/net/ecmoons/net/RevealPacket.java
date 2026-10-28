package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> client: Revealing Moon timing. The server decides when the eye searches; clients only animate.
 * WARN: a scan happens in {@code ticks} ticks (eyelids close so they are shut at the scan).
 * SCAN: the scan happened; the eye opens and the markers are shown for {@code ticks} ticks.
 * BLINK: play one blink (preview command). STOP: the moon ended - clear markers and reset the eye.
 */
public class RevealPacket {
    public static final byte WARN = 0, SCAN = 1, BLINK = 2, STOP = 3;

    public final byte stage;
    public final int ticks;
    public final List<Vector3d> markers;

    public RevealPacket(byte stage, int ticks, List<Vector3d> markers) {
        this.stage = stage;
        this.ticks = ticks;
        this.markers = markers;
    }

    public static void encode(RevealPacket p, PacketBuffer buf) {
        buf.writeByte(p.stage);
        buf.writeVarInt(p.ticks);
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
        int n = Math.min(buf.readVarInt(), 1024);
        List<Vector3d> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(new Vector3d(buf.readFloat(), buf.readFloat(), buf.readFloat()));
        return new RevealPacket(stage, ticks, list);
    }

    public static void handle(RevealPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.RevealClient.onPacket(p.stage, p.ticks, p.markers)));
        ctx.get().setPacketHandled(true);
    }
}
