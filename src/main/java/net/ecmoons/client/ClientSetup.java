package net.ecmoons.client;

import net.minecraftforge.common.MinecraftForge;

/** Registers client-only listeners. Only ever called through DistExecutor on the physical client. */
public final class ClientSetup {
    private ClientSetup() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.register(ClientEvents.class);
        MinecraftForge.EVENT_BUS.register(RevealClient.class);
        MinecraftForge.EVENT_BUS.register(ThreadClient.class);
        if (Boolean.getBoolean("ecmoons.clienttest")) MinecraftForge.EVENT_BUS.register(ClientSelfTest.class);
    }
}
