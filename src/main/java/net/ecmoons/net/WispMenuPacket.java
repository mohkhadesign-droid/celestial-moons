package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: open the Wisp menu with the pilot cooldown and the player's observers (number, distance, in range). */
public class WispMenuPacket {
    public final int cooldown, max;
    public final int[] numbers, ids;
    public final float[] distances;
    public final boolean[] inRange;

    public WispMenuPacket(int cooldown, int max, int[] numbers, int[] ids, float[] distances, boolean[] inRange) {
        this.cooldown = cooldown;
        this.max = max;
        this.numbers = numbers;
        this.ids = ids;
        this.distances = distances;
        this.inRange = inRange;
    }

    public static void encode(WispMenuPacket p, PacketBuffer buf) {
        buf.writeVarInt(p.cooldown);
        buf.writeVarInt(p.max);
        buf.writeVarInt(p.numbers.length);
        for (int i = 0; i < p.numbers.length; i++) {
            buf.writeVarInt(p.numbers[i]);
            buf.writeVarInt(p.ids[i]);
            buf.writeFloat(p.distances[i]);
            buf.writeBoolean(p.inRange[i]);
        }
    }

    public static WispMenuPacket decode(PacketBuffer buf) {
        int cd = buf.readVarInt(), max = buf.readVarInt(), n = Math.min(16, buf.readVarInt());
        int[] numbers = new int[n], ids = new int[n];
        float[] d = new float[n];
        boolean[] r = new boolean[n];
        for (int i = 0; i < n; i++) {
            numbers[i] = buf.readVarInt();
            ids[i] = buf.readVarInt();
            d[i] = buf.readFloat();
            r[i] = buf.readBoolean();
        }
        return new WispMenuPacket(cd, max, numbers, ids, d, r);
    }

    public static void handle(WispMenuPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> net.ecmoons.client.ability.WispClient.onMenu(p)));
        ctx.get().setPacketHandled(true);
    }
}
