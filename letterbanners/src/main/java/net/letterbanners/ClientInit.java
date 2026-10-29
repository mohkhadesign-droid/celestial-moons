package net.letterbanners;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderTypeLookup;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client only: banner hems have transparent notches (cutout), and the optional automated client test. */
public final class ClientInit {
    private ClientInit() {}

    public static void init(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(() -> {
            for (RegistryObject<net.minecraft.block.Block> b : LetterBanners.ALL) RenderTypeLookup.setRenderLayer(b.get(), RenderType.cutout());
        }));
        if (Boolean.getBoolean("letterbanners.clienttest")) MinecraftForge.EVENT_BUS.register(ClientTest.class);
    }
}
