package net.ecmoons.feature;

import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Settings;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeMod;

import java.util.UUID;

/**
 * Mist Moon server side: footstep sounds are broadcast to a smaller radius (ServerWorldMixin), and every player's
 * Forge name-tag distance attribute is lowered so vanilla renderers stop drawing name tags beyond the mist.
 * The fog itself is client-side (see client.ClientEvents). Damage and capture logic are never touched.
 */
public final class MistFeatures {
    public static final UUID NAMETAG_MODIFIER = UUID.fromString("6b1f3c3e-7f0e-4a52-9d0c-3e8c1d9b2a41");

    private MistFeatures() {}

    /** Called from ServerWorldMixin for every positional sound broadcast. */
    public static double footstepRadius(World world, SoundEvent sound, double radius) {
        if (sound == null || !isStepSound(sound)) return radius;
        if (!Moons.is(world, MoonType.MIST)) return radius;
        return radius * Settings.server().d("mist.footstepRangeMultiplier");
    }

    public static boolean isStepSound(SoundEvent sound) {
        ResourceLocation id = sound.getRegistryName();
        return id != null && id.getPath().endsWith(".step");
    }

    /** Keep the name-tag distance modifier in sync with the moon (called every second per player). */
    public static void updateNametag(ServerPlayerEntity player, boolean mist) {
        ModifiableAttributeInstance attr = player.getAttribute(ForgeMod.NAMETAG_DISTANCE.get());
        if (attr == null) return;
        AttributeModifier existing = attr.getModifier(NAMETAG_MODIFIER);
        if (mist) {
            double target = Settings.server().d("mist.nameplateMaxDistance") - 64.0; // base value is 64
            if (existing != null && existing.getAmount() == target) return;
            if (existing != null) attr.removeModifier(NAMETAG_MODIFIER);
            attr.addTransientModifier(new AttributeModifier(NAMETAG_MODIFIER, "ecmoons mist", target, AttributeModifier.Operation.ADDITION));
        } else if (existing != null) {
            attr.removeModifier(NAMETAG_MODIFIER);
        }
    }
}
