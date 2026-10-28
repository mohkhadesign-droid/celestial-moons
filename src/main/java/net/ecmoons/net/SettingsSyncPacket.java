package net.ecmoons.net;

import net.ecmoons.config.Matchers;
import net.ecmoons.config.Settings;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: the server's gameplay settings (fog distances, movement values, dimension lists...). */
public class SettingsSyncPacket {
    private final CompoundNBT tag;

    public SettingsSyncPacket(CompoundNBT tag) {
        this.tag = tag;
    }

    public static void encode(SettingsSyncPacket p, PacketBuffer buf) {
        buf.writeNbt(p.tag);
    }

    public static SettingsSyncPacket decode(PacketBuffer buf) {
        CompoundNBT tag = buf.readNbt();
        return new SettingsSyncPacket(tag == null ? new CompoundNBT() : tag);
    }

    public static void handle(SettingsSyncPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Settings.setClient(Settings.fromNbt(p.tag));
            Matchers.invalidate();
        });
        ctx.get().setPacketHandled(true);
    }
}
