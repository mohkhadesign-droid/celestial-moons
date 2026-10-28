package net.ecmoons.event;

import com.google.common.collect.ImmutableSet;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import corgitaco.enhancedcelestials.api.EnhancedCelestialsRegistry;
import corgitaco.enhancedcelestials.api.client.ColorSettings;
import corgitaco.enhancedcelestials.api.lunarevent.LunarEvent;
import corgitaco.enhancedcelestials.api.lunarevent.LunarTextComponents;
import corgitaco.enhancedcelestials.lunarevent.client.MoonClientSettings;
import corgitaco.enhancedcelestials.util.CustomTranslationTextComponent;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.config.Settings;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextFormatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registers the seven new moons with Enhanced Celestials (codec registry + default events) and manages their
 * per-world JSON files. Scheduling values are deliberately in the same range as the originals so that ordinary
 * nights remain the norm (Enhanced Celestials also enforces minDaysBetweenLunarEvents between any two events).
 */
public final class DefaultEvents {
    public static final MoonType[] NEW_MOONS = {MoonType.MIST, MoonType.FROST, MoonType.ARCANE, MoonType.PROSPECTOR,
            MoonType.WISDOM, MoonType.QUIET, MoonType.DRIFT};

    private DefaultEvents() {}

    public static ResourceLocation id(MoonType type) {
        return new ResourceLocation(EcMoons.MOD_ID, type.id() + "_moon");
    }

    /** Must run on the main thread during common setup (Enhanced Celestials' registries are not thread-safe). */
    public static void register() {
        for (MoonType type : NEW_MOONS) {
            Registry.register(EnhancedCelestialsRegistry.LUNAR_EVENT, id(type), AddonMoonEvent.codecFor(type));
            EnhancedCelestialsRegistry.DEFAULT_EVENTS.put(id(type), createDefault(type));
        }
        EcMoons.LOGGER.info("Registered {} new lunar events with Enhanced Celestials", NEW_MOONS.length);
    }

    public static AddonMoonEvent createDefault(MoonType type) {
        Set<Integer> phases = ImmutableSet.of(0, 1, 2, 3, 5, 6, 7); // same as Enhanced Celestials' defaults (no new moon)
        String sky;
        double blend;
        double chance;
        TextFormatting colour;
        switch (type) {
            case MIST: sky = "b4bac4"; blend = 0.35; chance = 0.04; colour = TextFormatting.GRAY; break;
            case FROST: sky = "a8d8ff"; blend = 0.40; chance = 0.035; colour = TextFormatting.AQUA; break;
            case ARCANE: sky = "b46cff"; blend = 0.40; chance = 0.03; colour = TextFormatting.LIGHT_PURPLE; break;
            case PROSPECTOR: sky = "e09a50"; blend = 0.30; chance = 0.035; colour = TextFormatting.GOLD; break;
            case WISDOM: sky = "7070f0"; blend = 0.40; chance = 0.035; colour = TextFormatting.BLUE; break;
            case QUIET: sky = "d8f2e2"; blend = 0.25; chance = 0.04; colour = TextFormatting.GREEN; break;
            case DRIFT: sky = "c8c0f5"; blend = 0.35; chance = 0.03; colour = TextFormatting.DARK_AQUA; break;
            default: throw new IllegalArgumentException(type.name());
        }
        String key = type.id() + "_moon";
        MoonClientSettings client = new MoonClientSettings(new ColorSettings(sky, blend, "ffffff", 1.0), 20.0F,
                type.moonTexture(), null);
        LunarTextComponents text = new LunarTextComponents(
                new CustomTranslationTextComponent("ecmoons.name." + key),
                new CustomTranslationTextComponent("ecmoons.notification." + key + ".rise", Style.EMPTY.withColor(colour)),
                new CustomTranslationTextComponent("ecmoons.notification." + key + ".set"));
        return new AddonMoonEvent(type, client, 4, chance, phases, text, false);
    }

    /** Existing worlds already have an events folder, so Enhanced Celestials would never add the new moons. */
    public static void writeMissingFiles(Path eventsDir) {
        if (eventsDir == null || !Files.isDirectory(eventsDir)) return; // EC writes all defaults into a new folder
        for (MoonType type : NEW_MOONS) {
            String fileKey = id(type).toString().replace(":", "-");
            Path json = eventsDir.resolve(fileKey + ".json");
            Path toml = eventsDir.resolve(fileKey + ".toml");
            if (Files.exists(json) || Files.exists(toml)) continue;
            Optional<JsonElement> encoded = LunarEvent.CODEC.encodeStart(JsonOps.INSTANCE, createDefault(type)).result();
            if (!encoded.isPresent()) {
                EcMoons.LOGGER.error("Could not encode default lunar event {}", fileKey);
                continue;
            }
            try {
                Files.write(json, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                        .toJson(encoded.get()).getBytes(StandardCharsets.UTF_8));
                EcMoons.LOGGER.info("Added missing lunar event file {}", json);
            } catch (IOException e) {
                EcMoons.LOGGER.error("Could not write {}: {}", json, e.toString());
            }
        }
    }

    /** Remove events switched off in ecmoons-common.toml from a freshly loaded server-side event map. */
    public static void removeDisabled(Map<String, LunarEvent> events, ResourceLocation worldId) {
        Settings s = Settings.server();
        List<String> extra = s.list("general.disabledEventKeys");
        List<String> removed = new ArrayList<>();
        for (Iterator<Map.Entry<String, LunarEvent>> it = events.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, LunarEvent> e = it.next();
            MoonType type = MoonType.of(e.getValue());
            boolean remove = extra.contains(e.getKey());
            if (!remove && type != MoonType.NONE) {
                if (MoonType.isSuperVariant(e.getValue())) {
                    remove = !s.b(type.id() + ".enabled") || (type.isOriginal() && !s.b(type.id() + ".superVariantEnabled"));
                } else {
                    remove = !s.b(type.id() + ".enabled");
                }
            }
            if (remove) {
                removed.add(e.getKey());
                it.remove();
            }
        }
        if (!removed.isEmpty()) EcMoons.LOGGER.info("[{}] lunar events disabled by ecmoons config: {}", worldId, removed);
    }
}
