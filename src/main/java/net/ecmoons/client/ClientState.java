package net.ecmoons.client;

import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side state received from the server. Deliberately free of client-only Minecraft classes so that common
 * code may reference it without loading anything client-only on a dedicated server.
 */
public final class ClientState {
    /** Levels shown by the last enchanting tooltip "N Enchantment Levels" line (diagnostics / client test). */
    public static volatile int lastTooltipLevels = -1;
    private static byte frostState;
    private static float frostProgress;
    private static final Map<BlockPos, Boolean> NATURAL = new LinkedHashMap<BlockPos, Boolean>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockPos, Boolean> eldest) {
            return size() > 64;
        }
    };

    private ClientState() {}

    public static void setFrost(byte state, float progress) {
        frostState = state;
        frostProgress = progress;
    }

    public static byte frostState() {
        return frostState;
    }

    public static float frostProgress() {
        return frostProgress;
    }

    public static synchronized void setNatural(BlockPos pos, boolean natural) {
        NATURAL.put(pos, natural);
    }

    /** Unknown blocks are predicted as NOT natural (normal speed) until the server says otherwise. */
    public static synchronized boolean isNatural(BlockPos pos) {
        Boolean b = NATURAL.get(pos);
        return b != null && b;
    }

    public static synchronized void reset() {
        frostState = 0;
        frostProgress = 0;
        NATURAL.clear();
    }
}
