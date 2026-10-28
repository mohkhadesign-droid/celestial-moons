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
        return CODECS.computeIfAbsent(type, t -> RecordCodecBuilder.create(builder -> builder.group(
                LunarEventClientSettings.CODEC.fieldOf("clientSettings").forGetter(LunarEvent::getClientSettings),
                Codec.INT.fieldOf("minNumberOfNightsBetween").forGetter(LunarEvent::getMinNumberOfNightsBetween),
                Codec.DOUBLE.fieldOf("chance").forGetter(LunarEvent::getChance),
                Codec.list(Codec.INT).fieldOf("validMoonPhases").forGetter(e -> new ArrayList<>(e.getValidMoonPhases())),
                LunarTextComponents.CODEC.fieldOf("textComponents").forGetter(LunarEvent::getTextComponents),
                Codec.BOOL.fieldOf("blockSleeping").forGetter(LunarEvent::blockSleeping)
        ).apply(builder, (clientSettings, minNights, chance, phases, text, blockSleeping) ->
                new AddonMoonEvent(t, clientSettings, minNights, chance, phases, text, blockSleeping))));
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
