package net.ecmoons.net;

import net.ecmoons.EcMoons;
import net.ecmoons.config.Settings;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.simple.SimpleChannel;

/**
 * The add-on's own channel. Its version must match on both sides, so a client without the add-on (or with a
 * different protocol) is refused at login with a clear message instead of failing later.
 */
public final class Network {
    public static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(EcMoons.MOD_ID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private Network() {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, SettingsSyncPacket.class, SettingsSyncPacket::encode, SettingsSyncPacket::decode, SettingsSyncPacket::handle);
        CHANNEL.registerMessage(id++, FrostStatePacket.class, FrostStatePacket::encode, FrostStatePacket::decode, FrostStatePacket::handle);
        CHANNEL.registerMessage(id++, NaturalBlockPacket.class, NaturalBlockPacket::encode, NaturalBlockPacket::decode, NaturalBlockPacket::handle);
    }

    public static void send(ServerPlayerEntity player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void syncSettings(ServerPlayerEntity player) {
        send(player, new SettingsSyncPacket(Settings.server().toNbt()));
    }

    public static void syncSettingsToAll(Iterable<ServerWorld> worlds) {
        SettingsSyncPacket packet = new SettingsSyncPacket(Settings.server().toNbt());
        for (ServerWorld w : worlds) for (ServerPlayerEntity p : w.players()) send(p, packet);
    }
}
