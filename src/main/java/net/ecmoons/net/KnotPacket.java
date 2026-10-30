package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client: the Knotbind tethers near the receiver (a public, hostile interaction: victims, caster and nearby
 * observers all see it so it can be countered). Per knot: id, the two entity ids, ticks left, both victims' unravel
 * progress (0..100). Never contains team or support data.
 */
public class KnotPacket {
    public final int[] data; // id, a, b, ticksLeft, unravelA, unravelB per knot

    public KnotPacket(int[] data) {
        this.data = data;
    }

    public static void encode(KnotPacket p, PacketBuffer buf) {
        buf.writeVarIntArray(p.data);
    }

    public static KnotPacket decode(PacketBuffer buf) {
        return new KnotPacket(buf.readVarIntArray(600));
    }

    public static void handle(KnotPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.ThreadClient.onKnots(p.data)));
        ctx.get().setPacketHandled(true);
    }
}
