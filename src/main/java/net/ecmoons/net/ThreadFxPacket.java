package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client: one Thread Moon effect at the players. Friendly kinds (Lifeline pulse, Guard pulse, Last Stitch
 * knot) go only to the team that owns them; KNOT_RELEASE (loose fibres) goes to the knot's nearby observers.
 */
public class ThreadFxPacket {
    public final byte kind;
    public final int a, b;
    public final float value;

    public ThreadFxPacket(byte kind, int a, int b, float value) {
        this.kind = kind;
        this.a = a;
        this.b = b;
        this.value = value;
    }

    public static void encode(ThreadFxPacket p, PacketBuffer buf) {
        buf.writeByte(p.kind);
        buf.writeVarInt(p.a);
        buf.writeVarInt(p.b);
        buf.writeFloat(p.value);
    }

    public static ThreadFxPacket decode(PacketBuffer buf) {
        return new ThreadFxPacket(buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readFloat());
    }

    public static void handle(ThreadFxPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.ThreadClient.onFx(p.kind, p.a, p.b, p.value)));
        ctx.get().setPacketHandled(true);
    }
}
