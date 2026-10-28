package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client (the ability's user only): what the small HUD next to the crosshair should show.
 * kind: 0 none, 1 Afterimage, 2 Shell. The meaning of a..d depends on kind (see AbilityHud).
 */
public class AbilityStatePacket {
    public final byte kind;
    public final int a, b, c, d;

    public AbilityStatePacket(byte kind, int a, int b, int c, int d) {
        this.kind = kind;
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
    }

    public static void encode(AbilityStatePacket p, PacketBuffer buf) {
        buf.writeByte(p.kind);
        buf.writeVarInt(p.a);
        buf.writeVarInt(p.b);
        buf.writeVarInt(p.c);
        buf.writeVarInt(p.d);
    }

    public static AbilityStatePacket decode(PacketBuffer buf) {
        return new AbilityStatePacket(buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(AbilityStatePacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.ability.AbilityClient.onState(p.kind, p.a, p.b, p.c, p.d)));
        ctx.get().setPacketHandled(true);
    }
}
