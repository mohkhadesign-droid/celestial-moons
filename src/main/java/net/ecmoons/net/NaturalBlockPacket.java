package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client: whether the block the player just started mining is natural. Lets the client predict
 * Prospector's Moon mining speed without the client ever deciding eligibility (the server re-checks every break).
 */
public class NaturalBlockPacket {
    public final BlockPos pos;
    public final boolean natural;

    public NaturalBlockPacket(BlockPos pos, boolean natural) {
        this.pos = pos;
        this.natural = natural;
    }

    public static void encode(NaturalBlockPacket p, PacketBuffer buf) {
        buf.writeBlockPos(p.pos);
        buf.writeBoolean(p.natural);
    }

    public static NaturalBlockPacket decode(PacketBuffer buf) {
        return new NaturalBlockPacket(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(NaturalBlockPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.ClientState.setNatural(p.pos, p.natural)));
        ctx.get().setPacketHandled(true);
    }
}
