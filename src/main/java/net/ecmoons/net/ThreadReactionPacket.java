package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> every client in a dimension: the moon cat's next reaction. Only the reaction type, the event id (hash),
 * a sequence number and the game time it starts - no player, team, position or direction.
 */
public class ThreadReactionPacket {
    public final byte type;
    public final int event, seq;
    public final long start;

    public ThreadReactionPacket(byte type, int event, int seq, long start) {
        this.type = type;
        this.event = event;
        this.seq = seq;
        this.start = start;
    }

    public static void encode(ThreadReactionPacket p, PacketBuffer buf) {
        buf.writeByte(p.type);
        buf.writeInt(p.event);
        buf.writeVarInt(p.seq);
        buf.writeLong(p.start);
    }

    public static ThreadReactionPacket decode(PacketBuffer buf) {
        return new ThreadReactionPacket(buf.readByte(), buf.readInt(), buf.readVarInt(), buf.readLong());
    }

    public static void handle(ThreadReactionPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.ThreadMoonSky.onReaction(p.type, p.event, p.seq, p.start)));
        ctx.get().setPacketHandled(true);
    }
}
