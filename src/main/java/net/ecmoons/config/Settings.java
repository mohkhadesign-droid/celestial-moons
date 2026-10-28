package net.ecmoons.config;

import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of {@link MoonConfig}. The server builds it from its config file; clients receive the
 * server's snapshot over the network (so a client's own common config never changes gameplay).
 */
public final class Settings {
    private static volatile Settings server = defaults();
    private static volatile Settings client = defaults();

    private final Map<String, Object> values;

    private Settings(Map<String, Object> values) {
        this.values = values;
    }

    public static Settings server() {
        return server;
    }

    public static Settings client() {
        return client;
    }

    public static Settings get(@Nullable World world) {
        return world != null && world.isClientSide ? client : server;
    }

    public static void setClient(Settings settings) {
        client = settings;
    }

    /** Rebuild the server snapshot from the loaded config file. */
    public static void reloadServer() {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, ForgeConfigSpec.ConfigValue<?>> e : MoonConfig.VALUES.entrySet()) {
            Object v;
            try {
                v = e.getValue().get();
            } catch (IllegalStateException notLoaded) {
                v = defaultOf(e.getValue());
            }
            map.put(e.getKey(), normalise(v));
        }
        server = new Settings(map);
        EcMoons.LOGGER.debug("Settings snapshot rebuilt ({} values)", map.size());
    }

    public static void resetClient() {
        client = defaults();
    }

    private static Settings defaults() {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, ForgeConfigSpec.ConfigValue<?>> e : MoonConfig.VALUES.entrySet()) {
            map.put(e.getKey(), normalise(defaultOf(e.getValue())));
        }
        return new Settings(map);
    }

    private static Object defaultOf(ForgeConfigSpec.ConfigValue<?> value) {
        Object spec = MoonConfig.SPEC.getSpec().get(value.getPath());
        if (spec instanceof ForgeConfigSpec.ValueSpec) return ((ForgeConfigSpec.ValueSpec) spec).getDefault();
        return null;
    }

    private static Object normalise(Object v) {
        if (v instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object o : (List<?>) v) out.add(String.valueOf(o));
            return Collections.unmodifiableList(out);
        }
        return v;
    }

    // ---- typed access -------------------------------------------------------------------------------------

    private Object raw(String key) {
        Object v = values.get(key);
        if (v == null) throw new IllegalArgumentException("Unknown ecmoons setting: " + key);
        return v;
    }

    public double d(String key) {
        return ((Number) raw(key)).doubleValue();
    }

    public int i(String key) {
        return ((Number) raw(key)).intValue();
    }

    public boolean b(String key) {
        return (Boolean) raw(key);
    }

    public String s(String key) {
        return String.valueOf(raw(key));
    }

    @SuppressWarnings("unchecked")
    public List<String> list(String key) {
        return (List<String>) raw(key);
    }

    public boolean superInherits() {
        return b("general.superVariantsInheritCustomFeatures");
    }

    public boolean moonEnabled(MoonType type) {
        return type != MoonType.NONE && b(type.id() + ".enabled");
    }

    public boolean dimensionAllowed(MoonType type, @Nullable ResourceLocation dimension) {
        List<String> dims = list(type.id() + ".dimensions");
        return dims.isEmpty() || (dimension != null && dims.contains(dimension.toString()));
    }

    // ---- network ------------------------------------------------------------------------------------------

    public CompoundNBT toNbt() {
        CompoundNBT tag = new CompoundNBT();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Boolean) tag.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) tag.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Number) tag.putDouble(e.getKey(), ((Number) v).doubleValue());
            else if (v instanceof List) {
                ListNBT list = new ListNBT();
                for (Object o : (List<?>) v) list.add(StringNBT.valueOf(String.valueOf(o)));
                tag.put(e.getKey(), list);
            } else if (v != null) tag.putString(e.getKey(), String.valueOf(v));
        }
        return tag;
    }

    public static Settings fromNbt(CompoundNBT tag) {
        Settings base = defaults();
        Map<String, Object> map = new HashMap<>(base.values);
        for (String key : tag.getAllKeys()) {
            Object def = map.get(key);
            if (def == null) continue; // unknown key from a different add-on version: ignore
            if (def instanceof Boolean) map.put(key, tag.getBoolean(key));
            else if (def instanceof Integer) map.put(key, tag.getInt(key));
            else if (def instanceof Number) map.put(key, tag.getDouble(key));
            else if (def instanceof List) {
                ListNBT list = tag.getList(key, Constants.NBT.TAG_STRING);
                List<String> out = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
                map.put(key, Collections.unmodifiableList(out));
            } else map.put(key, tag.getString(key));
        }
        return new Settings(map);
    }
}
