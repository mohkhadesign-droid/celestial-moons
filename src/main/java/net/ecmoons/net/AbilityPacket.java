package net.ecmoons.net;

import net.ecmoons.feature.ability.MoonAbilities;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server: the Moon Ability key went down or up. The client only reports the key and the direction the
 * player is trying to move (its movement keys); every rule, timing and distance is decided on the server.
 */
public class AbilityPacket {
    public static final byte PRESS = 0, RELEASE = 1;
    public final byte action;
    public final float dirX, dirZ;

    public AbilityPacket(byte action, float dirX, float dirZ) {
        this.action = action;
        this.dirX = dirX;
        this.dirZ = dirZ;
    }

    public static void encode(AbilityPacket p, PacketBuffer buf) {
        buf.writeByte(p.action);
        buf.writeFloat(p.dirX);
        buf.writeFloat(p.dirZ);
    }

    public static AbilityPacket decode(PacketBuffer buf) {
        return new AbilityPacket(buf.readByte(), buf.readFloat(), buf.readFloat());
    }

    public static void handle(AbilityPacket p, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayerEntity sender = ctx.get().getSender();
        ctx.get().enqueueWork(() -> {
            if (sender != null && Float.isFinite(p.dirX) && Float.isFinite(p.dirZ)) MoonAbilities.onKey(sender, p.action, p.dirX, p.dirZ);
        });
        ctx.get().setPacketHandled(true);
    }
}
