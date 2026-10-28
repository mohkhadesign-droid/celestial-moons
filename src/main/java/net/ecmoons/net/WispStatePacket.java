package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client (that player only): piloting (camera on the wisp entity), viewing (camera on an observer, which
 * faces baseYaw/basePitch), or back in the body; plus the observer count and the pilot cooldown for the HUD.
 */
public class WispStatePacket {
    public static final byte NONE = 0, PILOT = 1, VIEW = 2;
    public final byte mode;
    public final int entityId;
    public final float baseYaw, basePitch;
    public final int number, count, max, cooldown;

    public WispStatePacket(byte mode, int entityId, float baseYaw, float basePitch, int number, int count, int max, int cooldown) {
        this.mode = mode;
        this.entityId = entityId;
        this.baseYaw = baseYaw;
        this.basePitch = basePitch;
        this.number = number;
        this.count = count;
        this.max = max;
        this.cooldown = cooldown;
    }

    public static void encode(WispStatePacket p, PacketBuffer buf) {
        buf.writeByte(p.mode);
        buf.writeVarInt(p.entityId);
        buf.writeFloat(p.baseYaw);
        buf.writeFloat(p.basePitch);
        buf.writeVarInt(p.number);
        buf.writeVarInt(p.count);
        buf.writeVarInt(p.max);
        buf.writeVarInt(p.cooldown);
    }

    public static WispStatePacket decode(PacketBuffer buf) {
        return new WispStatePacket(buf.readByte(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(WispStatePacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.ecmoons.client.ability.WispClient.onState(p)));
        ctx.get().setPacketHandled(true);
    }
}
