package net.ecmoons.net;

import net.ecmoons.feature.thread.ThreadMoon;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server: Thread Moon key input. Only requests: the server validates teams, range, line of sight, aim,
 * eligibility, permissions, cooldowns and resources itself.
 */
public class ThreadInputPacket {
    public static final byte LIFELINE_START = 0, LIFELINE_STOP = 1, GUARD_TOGGLE = 2, KNOT_FIRE = 3,
            UNRAVEL_START = 4, UNRAVEL_STOP = 5, UNRAVEL_RESET = 6;
    public final byte action;
    public final int target;

    public ThreadInputPacket(byte action, int target) {
        this.action = action;
        this.target = target;
    }

    public static void encode(ThreadInputPacket p, PacketBuffer buf) {
        buf.writeByte(p.action);
        buf.writeVarInt(p.target + 1);
    }

    public static ThreadInputPacket decode(PacketBuffer buf) {
        return new ThreadInputPacket(buf.readByte(), buf.readVarInt() - 1);
    }

    public static void handle(ThreadInputPacket p, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayerEntity sender = ctx.get().getSender();
        if (sender != null) ctx.get().enqueueWork(() -> ThreadMoon.onInput(sender, p.action, p.target));
        ctx.get().setPacketHandled(true);
    }
}
