package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client (only to members of the team): the receiver's current Thread Moon damage reduction (for the HUD)
 * and the entity id pairs of the team's bonded links (for the rose thread particles). Other teams never receive it.
 */
public class ThreadPacket {
    public final float reduction;
    public final int[] links; // a0, b0, a1, b1, ...

    public ThreadPacket(float reduction, int[] links) {
        this.reduction = reduction;
        this.links = links;
    }

    public static void encode(ThreadPacket p, PacketBuffer buf) {
        buf.writeFloat(p.reduction);
        buf.writeVarIntArray(p.links);
    }

    public static ThreadPacket decode(PacketBuffer buf) {
        return new ThreadPacket(buf.readFloat(), buf.readVarIntArray(512));
    }

    public static void handle(ThreadPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.ThreadClient.update(p.reduction, p.links)));
        ctx.get().setPacketHandled(true);
    }
}
