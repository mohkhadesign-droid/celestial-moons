package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client: visual-only atmosphere signals. The server never renders anything; it only tells clients about
 * real gameplay moments (War Moon phases, a Gluttony food counted) and admin previews.
 */
public class AtmoPacket {
    public static final byte PREVIEW = 0, DEMO = 1, WAR = 2, GLUTTONY = 3;
    public static final int WAR_IDLE = 0, WAR_WARNING = 1, WAR_SUMMON = 2, WAR_FIGHT = 3;
    public static final int DEMO_REVEAL = 1, DEMO_WAR = 2, DEMO_FEAST = 3;

    public final byte kind;
    public final int a, b, c;

    public AtmoPacket(byte kind, int a, int b, int c) {
        this.kind = kind;
        this.a = a;
        this.b = b;
        this.c = c;
    }

    public static void encode(AtmoPacket p, PacketBuffer buf) {
        buf.writeByte(p.kind);
        buf.writeVarInt(p.a);
        buf.writeVarInt(p.b);
        buf.writeVarInt(p.c);
    }

    public static AtmoPacket decode(PacketBuffer buf) {
        return new AtmoPacket(buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(AtmoPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.atmo.AtmoEvents.onPacket(p.kind, p.a, p.b, p.c)));
        ctx.get().setPacketHandled(true);
    }
}
