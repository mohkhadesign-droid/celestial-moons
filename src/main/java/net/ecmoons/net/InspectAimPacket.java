package net.ecmoons.net;

import net.ecmoons.feature.reveal.ReadYourEnemy;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server: Read Your Enemy aim. The entity id of the player the client is aiming at, or -1. It is only a
 * proposal: the server validates the reveal, eligibility, range, aim and line of sight itself every tick.
 */
public class InspectAimPacket {
    public final int targetId;

    public InspectAimPacket(int targetId) {
        this.targetId = targetId;
    }

    public static void encode(InspectAimPacket p, PacketBuffer buf) {
        buf.writeVarInt(p.targetId + 1);
    }

    public static InspectAimPacket decode(PacketBuffer buf) {
        return new InspectAimPacket(buf.readVarInt() - 1);
    }

    public static void handle(InspectAimPacket p, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayerEntity sender = ctx.get().getSender();
        if (sender != null) ctx.get().enqueueWork(() -> ReadYourEnemy.onAim(sender, p.targetId));
        ctx.get().setPacketHandled(true);
    }
}
