package net.ecmoons.net;

import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server -> client: Read Your Enemy. Only what the panel shows: the target's name, health, maximum health,
 * absorption and active effects (id, amplifier, remaining ticks). targetId -1 = hide the panel now.
 */
public class InspectPacket {
    public static final class Effect {
        public final ResourceLocation id;
        public final int amplifier, duration;

        public Effect(ResourceLocation id, int amplifier, int duration) {
            this.id = id;
            this.amplifier = amplifier;
            this.duration = duration;
        }
    }

    public final int targetId;
    public final String name;
    public final float health, maxHealth, absorption;
    public final List<Effect> effects;
    public final int more;

    public InspectPacket(int targetId, String name, float health, float maxHealth, float absorption, List<Effect> effects, int more) {
        this.targetId = targetId;
        this.name = name;
        this.health = health;
        this.maxHealth = maxHealth;
        this.absorption = absorption;
        this.effects = effects;
        this.more = more;
    }

    public static InspectPacket clear() {
        return new InspectPacket(-1, "", 0, 0, 0, Collections.emptyList(), 0);
    }

    public static void encode(InspectPacket p, PacketBuffer buf) {
        buf.writeVarInt(p.targetId + 1);
        if (p.targetId < 0) return;
        buf.writeUtf(p.name, 64);
        buf.writeFloat(p.health);
        buf.writeFloat(p.maxHealth);
        buf.writeFloat(p.absorption);
        buf.writeVarInt(p.effects.size());
        for (Effect e : p.effects) {
            buf.writeResourceLocation(e.id);
            buf.writeVarInt(e.amplifier);
            buf.writeVarInt(e.duration);
        }
        buf.writeVarInt(p.more);
    }

    public static InspectPacket decode(PacketBuffer buf) {
        int id = buf.readVarInt() - 1;
        if (id < 0) return clear();
        String name = buf.readUtf(64);
        float h = buf.readFloat(), m = buf.readFloat(), a = buf.readFloat();
        int n = Math.min(buf.readVarInt(), 32);
        List<Effect> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(new Effect(buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt()));
        return new InspectPacket(id, name, h, m, a, list, buf.readVarInt());
    }

    public static void handle(InspectPacket p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.ecmoons.client.RevealHud.onInspect(p)));
        ctx.get().setPacketHandled(true);
    }
}
