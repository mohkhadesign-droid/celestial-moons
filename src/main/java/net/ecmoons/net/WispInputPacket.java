package net.ecmoons.net;

import net.ecmoons.feature.ability.WispAbility;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/** Client -> server, every tick while piloting: the movement keys and where the pilot looks. The server moves the wisp. */
public class WispInputPacket {
    public final float forward, strafe, up, yaw, pitch;

    public WispInputPacket(float forward, float strafe, float up, float yaw, float pitch) {
        this.forward = forward;
        this.strafe = strafe;
        this.up = up;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static void encode(WispInputPacket p, PacketBuffer buf) {
        buf.writeFloat(p.forward);
        buf.writeFloat(p.strafe);
        buf.writeFloat(p.up);
        buf.writeFloat(p.yaw);
        buf.writeFloat(p.pitch);
    }

    public static WispInputPacket decode(PacketBuffer buf) {
        return new WispInputPacket(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(WispInputPacket p, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayerEntity sender = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (sender != null) WispAbility.onInput(sender, p.forward, p.strafe, p.up, p.yaw, p.pitch);
        });
        ctx.get().setPacketHandled(true);
    }
}
