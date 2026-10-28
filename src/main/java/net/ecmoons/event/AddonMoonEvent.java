package net.ecmoons.event;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import corgitaco.enhancedcelestials.api.lunarevent.LunarEvent;
import corgitaco.enhancedcelestials.api.lunarevent.LunarTextComponents;
import corgitaco.enhancedcelestials.api.lunarevent.client.LunarEventClientSettings;
import net.ecmoons.MoonType;
import net.ecmoons.config.Settings;
import net.minecraft.entity.EntityClassification;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One of the seven new moons. Scheduling, forecasting, notifications, sleep blocking, sky-light colour and
 * moon size are all handled by Enhanced Celestials from the event's JSON file; the moon's gameplay effects are
 * implemented by this add-on and configured in config/ecmoons-common.toml.
 * <p>
 * The JSON format is exactly the base Enhanced Celestials event format (no extra fields), so the files stay
 * editable with the same knowledge as the original events.
 */
public class AddonMoonEvent extends LunarEvent {
    private static final Map<MoonType, Codec<AddonMoonEvent>> CODECS = new EnumMap<>(MoonType.class);

    private final MoonType moonType;
    /** Dimension of the lunar context that owns this instance (set right after the context loads its events). */
    @Nullable private ResourceLocation dimension;

    public AddonMoonEvent(MoonType moonType, LunarEventClientSettings clientSettings, int minNumberOfNightsBetween, double chance,
                          Collection<Integer> validMoonPhases, LunarTextComponents textComponents, boolean blockSleeping) {
        super(clientSettings, minNumberOfNightsBetween, chance, validMoonPhases, textComponents, blockSleeping);
        this.moonType = moonType;
    }

    public static synchronized Codec<AddonMoonEvent> codecFor(MoonType type) {
        Codec<AddonMoonEvent> codec = CODECS.get(type);
        if (codec == null) {
            codec = build(type);
            CODECS.put(type, codec);
        }
        return codec;
    }

    // Explicitly typed lambdas: javac 8's inference is extremely slow on untyped nested codec builders.
    private static Codec<AddonMoonEvent> build(final MoonType type) {
        return RecordCodecBuilder.create((RecordCodecBuilder.Instance<AddonMoonEvent> builder) -> builder.group(
                LunarEventClientSettings.CODEC.fieldOf("clientSettings").forGetter((AddonMoonEvent e) -> e.getClientSettings()),
                Codec.INT.fieldOf("minNumberOfNightsBetween").forGetter((AddonMoonEvent e) -> e.getMinNumberOfNightsBetween()),
                Codec.DOUBLE.fieldOf("chance").forGetter((AddonMoonEvent e) -> e.getChance()),
                Codec.list(Codec.INT).fieldOf("validMoonPhases").forGetter((AddonMoonEvent e) -> new ArrayList<Integer>(e.getValidMoonPhases())),
                LunarTextComponents.CODEC.fieldOf("textComponents").forGetter((AddonMoonEvent e) -> e.getTextComponents()),
                Codec.BOOL.fieldOf("blockSleeping").forGetter((AddonMoonEvent e) -> e.blockSleeping())
        ).apply(builder, (LunarEventClientSettings clientSettings, Integer minNights, Double chance, List<Integer> phases,
                          LunarTextComponents text, Boolean blockSleeping) ->
                new AddonMoonEvent(type, clientSettings, minNights, chance, phases, text, blockSleeping)));
    }

    @Override
    public Codec<? extends LunarEvent> codec() {
        return codecFor(moonType);
    }

    public MoonType getMoonType() {
        return moonType;
    }

    public void setDimension(@Nullable ResourceLocation dimension) {
        this.dimension = dimension;
    }

    /**
     * Quiet Moon lowers the natural hostile cap through Enhanced Celestials' own spawn-cap hook
     * (MixinEntityDensityManager), so existing monsters and every other spawn path are untouched.
     */
    @Override
    public double getSpawnMultiplierForMonsterCategory(EntityClassification classification) {
        if (moonType == MoonType.QUIET && classification == EntityClassification.MONSTER) {
            Settings s = Settings.server();
            if (s.moonEnabled(MoonType.QUIET) && s.dimensionAllowed(MoonType.QUIET, dimension) && (!isSuper() || s.superInherits())) {
                return s.d("quiet.monsterCapMultiplier");
            }
        }
        return 1.0;
    }

    private boolean isSuper() {
        return MoonType.isSuperVariant(this);
    }
}
