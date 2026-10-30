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
    public static final String PROTOCOL = "7";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(EcMoons.MOD_ID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private Network() {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, SettingsSyncPacket.class, SettingsSyncPacket::encode, SettingsSyncPacket::decode, SettingsSyncPacket::handle);
        CHANNEL.registerMessage(id++, FrostStatePacket.class, FrostStatePacket::encode, FrostStatePacket::decode, FrostStatePacket::handle);
        CHANNEL.registerMessage(id++, NaturalBlockPacket.class, NaturalBlockPacket::encode, NaturalBlockPacket::decode, NaturalBlockPacket::handle);
        CHANNEL.registerMessage(id++, RevealPacket.class, RevealPacket::encode, RevealPacket::decode, RevealPacket::handle);
        CHANNEL.registerMessage(id++, AtmoPacket.class, AtmoPacket::encode, AtmoPacket::decode, AtmoPacket::handle);
        CHANNEL.registerMessage(id++, AbilityPacket.class, AbilityPacket::encode, AbilityPacket::decode, AbilityPacket::handle);
        CHANNEL.registerMessage(id++, AbilityStatePacket.class, AbilityStatePacket::encode, AbilityStatePacket::decode, AbilityStatePacket::handle);
        CHANNEL.registerMessage(id++, AbilityFxPacket.class, AbilityFxPacket::encode, AbilityFxPacket::decode, AbilityFxPacket::handle);
        CHANNEL.registerMessage(id++, ThreadPacket.class, ThreadPacket::encode, ThreadPacket::decode, ThreadPacket::handle);
        CHANNEL.registerMessage(id++, WispInputPacket.class, WispInputPacket::encode, WispInputPacket::decode, WispInputPacket::handle);
        CHANNEL.registerMessage(id++, WispActionPacket.class, WispActionPacket::encode, WispActionPacket::decode, WispActionPacket::handle);
        CHANNEL.registerMessage(id++, WispStatePacket.class, WispStatePacket::encode, WispStatePacket::decode, WispStatePacket::handle);
        CHANNEL.registerMessage(id++, WispMenuPacket.class, WispMenuPacket::encode, WispMenuPacket::decode, WispMenuPacket::handle);
        CHANNEL.registerMessage(id++, InspectPacket.class, InspectPacket::encode, InspectPacket::decode, InspectPacket::handle);
        CHANNEL.registerMessage(id++, InspectAimPacket.class, InspectAimPacket::encode, InspectAimPacket::decode, InspectAimPacket::handle);
        CHANNEL.registerMessage(id++, ThreadFxPacket.class, ThreadFxPacket::encode, ThreadFxPacket::decode, ThreadFxPacket::handle);
        CHANNEL.registerMessage(id++, KnotPacket.class, KnotPacket::encode, KnotPacket::decode, KnotPacket::handle);
        CHANNEL.registerMessage(id++, ThreadReactionPacket.class, ThreadReactionPacket::encode, ThreadReactionPacket::decode, ThreadReactionPacket::handle);
        CHANNEL.registerMessage(id++, ThreadInputPacket.class, ThreadInputPacket::encode, ThreadInputPacket::decode, ThreadInputPacket::handle);
    }

    public static void send(ServerPlayerEntity player, Object packet) {
        if (player.connection == null || player instanceof net.minecraftforge.common.util.FakePlayer) return; // test/fake players
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
