package net.ecmoons.net;

import net.ecmoons.feature.ability.WispAbility;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server: a Wisp menu choice (pilot, view n, dismiss n/all), the placement input, or "return to body". */
public class WispActionPacket {
    public final byte action;
    public final int index;

    public WispActionPacket(byte action, int index) {
        this.action = action;
        this.index = index;
    }

    public static void encode(WispActionPacket p, PacketBuffer buf) {
        buf.writeByte(p.action);
        buf.writeVarInt(p.index);
    }

    public static WispActionPacket decode(PacketBuffer buf) {
        return new WispActionPacket(buf.readByte(), buf.readVarInt());
    }

    public static void handle(WispActionPacket p, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayerEntity sender = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (sender != null) WispAbility.onAction(sender, p.action, p.index);
        });
        ctx.get().setPacketHandled(true);
    }
}
