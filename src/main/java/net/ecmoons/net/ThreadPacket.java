package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> one client: that player's Thread Moon state. Friendly support data (bonded links, guard pairs) is built
 * only from the receiver's OWN team, so other teams never receive it; the rest is the receiver's own HUD state.
 */
public class ThreadPacket {
    public float reduction;
    public int[] links = new int[0];        // a0, b0, a1, b1 ... (team links)
    public int[] guards = new int[0];       // guardian, target, active(0/1) ... (team guard pairs)
    public String guardTarget = "", guardedBy = "";
    public boolean guardActive, guardEstablished;
    public int lifelineTarget = -1;         // entity id while channelling
    public float lifelineAim, lifelineMoved;
    public int lifelineCooldown, guardCooldown, knotCooldown;
    public byte lastStitch;                 // 0 = not in a team, 1 = available, 2 = spent
    public int markTarget = -1, markTicks;  // your first Knotbind mark
    public int taggedTicks;                 // you are someone's first mark
    public float unravel;                   // your own unravel progress while knotted (-1 = not knotted)
    public int knotTicks;                   // time left of the knot you're in

    public static void encode(ThreadPacket p, PacketBuffer buf) {
        buf.writeFloat(p.reduction);
        buf.writeVarIntArray(p.links);
        buf.writeVarIntArray(p.guards);
        buf.writeUtf(p.guardTarget, 40);
        buf.writeUtf(p.guardedBy, 40);
        buf.writeBoolean(p.guardActive);
        buf.writeBoolean(p.guardEstablished);
        buf.writeVarInt(p.lifelineTarget + 1);
        buf.writeFloat(p.lifelineAim);
        buf.writeFloat(p.lifelineMoved);
        buf.writeVarInt(p.lifelineCooldown);
        buf.writeVarInt(p.guardCooldown);
        buf.writeVarInt(p.knotCooldown);
        buf.writeByte(p.lastStitch);
        buf.writeVarInt(p.markTarget + 1);
        buf.writeVarInt(p.markTicks);
        buf.writeVarInt(p.taggedTicks);
        buf.writeFloat(p.unravel);
        buf.writeVarInt(p.knotTicks);
    }

    public static ThreadPacket decode(PacketBuffer buf) {
        ThreadPacket p = new ThreadPacket();
        p.reduction = buf.readFloat();
        p.links = buf.readVarIntArray(512);
        p.guards = buf.readVarIntArray(384);
        p.guardTarget = buf.readUtf(40);
        p.guardedBy = buf.readUtf(40);
        p.guardActive = buf.readBoolean();
        p.guardEstablished = buf.readBoolean();
        p.lifelineTarget = buf.readVarInt() - 1;
        p.lifelineAim = buf.readFloat();
        p.lifelineMoved = buf.readFloat();
        p.lifelineCooldown = buf.readVarInt();
        p.guardCooldown = buf.readVarInt();
        p.knotCooldown = buf.readVarInt();
        p.lastStitch = buf.readByte();
        p.markTarget = buf.readVarInt() - 1;
        p.markTicks = buf.readVarInt();
        p.taggedTicks = buf.readVarInt();
        p.unravel = buf.readFloat();
        p.knotTicks = buf.readVarInt();
        return p;
    }

    /** A content hash, so unchanged state is not resent. */
    public int hash() {
        int h = Float.floatToIntBits(reduction);
        h = h * 31 + java.util.Arrays.hashCode(links);
        h = h * 31 + java.util.Arrays.hashCode(guards);
        h = h * 31 + guardTarget.hashCode();
        h = h * 31 + guardedBy.hashCode();
        h = h * 31 + (guardActive ? 1 : 0) + (guardEstablished ? 2 : 0);
        h = h * 31 + lifelineTarget;
        h = h * 31 + Float.floatToIntBits(lifelineAim) + Float.floatToIntBits(lifelineMoved) * 7;
        h = h * 31 + (lifelineCooldown > 0 ? 1 : 0) + (guardCooldown > 0 ? 2 : 0) + (knotCooldown > 0 ? 4 : 0);
        h = h * 31 + lastStitch;
        h = h * 31 + markTarget * 3 + (markTicks > 0 ? 1 : 0) + (taggedTicks > 0 ? 2 : 0);
        h = h * 31 + Float.floatToIntBits(unravel) + knotTicks / 20;
        return h;
    }

    public boolean empty() {
        return reduction == 0f && links.length == 0 && guards.length == 0 && guardTarget.isEmpty() && guardedBy.isEmpty()
                && lifelineTarget < 0 && lifelineCooldown == 0 && guardCooldown == 0 && knotCooldown == 0 && lastStitch != 2
                && markTarget < 0 && taggedTicks == 0 && unravel < 0;
    }

    public static void handle(ThreadPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.ThreadClient.update(p)));
        ctx.get().setPacketHandled(true);
    }
}
