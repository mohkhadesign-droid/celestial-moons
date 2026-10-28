package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: the local player's Frost Moon state, used only for the frost overlay intensity. */
public class FrostStatePacket {
    public static final byte NONE = 0, EXPOSED = 1, CHILLED = 2, WARM = 3, SHELTERED = 4;

    public final byte state;
    /** 0..1 progress towards being chilled. */
    public final float progress;

    public FrostStatePacket(byte state, float progress) {
        this.state = state;
        this.progress = progress;
    }

    public static void encode(FrostStatePacket p, PacketBuffer buf) {
        buf.writeByte(p.state);
        buf.writeFloat(p.progress);
    }

    public static FrostStatePacket decode(PacketBuffer buf) {
        return new FrostStatePacket(buf.readByte(), buf.readFloat());
    }

    public static void handle(FrostStatePacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.ClientState.setFrost(p.state, p.progress)));
        ctx.get().setPacketHandled(true);
    }
}
