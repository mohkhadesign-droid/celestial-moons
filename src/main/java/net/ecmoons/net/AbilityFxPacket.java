package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> nearby clients: visual-only ability effects.
 * GHOST: an afterimage of entity {@code id} at (x,y,z) facing yaw. SHELL_ADD / SHELL_UPDATE: shell {@code id} at
 * (x,y,z), radius {@code r}, ticks left {@code t}, durability fraction {@code f}. SHELL_HIT: flash at (x,y,z).
 * SHELL_REMOVE: shell {@code id} collapses.
 */
public class AbilityFxPacket {
    public static final byte GHOST = 0, SHELL_ADD = 1, SHELL_HIT = 2, SHELL_REMOVE = 3;
    public final byte kind;
    public final int id, t;
    public final double x, y, z;
    public final float yaw, r, f;

    public AbilityFxPacket(byte kind, int id, double x, double y, double z, float yaw, float r, int t, float f) {
        this.kind = kind;
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.r = r;
        this.t = t;
        this.f = f;
    }

    public static void encode(AbilityFxPacket p, PacketBuffer buf) {
        buf.writeByte(p.kind);
        buf.writeVarInt(p.id);
        buf.writeDouble(p.x);
        buf.writeDouble(p.y);
        buf.writeDouble(p.z);
        buf.writeFloat(p.yaw);
        buf.writeFloat(p.r);
        buf.writeVarInt(p.t);
        buf.writeFloat(p.f);
    }

    public static AbilityFxPacket decode(PacketBuffer buf) {
        return new AbilityFxPacket(buf.readByte(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readFloat());
    }

    public static void handle(AbilityFxPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.ability.AbilityFx.onPacket(p)));
        ctx.get().setPacketHandled(true);
    }
}
