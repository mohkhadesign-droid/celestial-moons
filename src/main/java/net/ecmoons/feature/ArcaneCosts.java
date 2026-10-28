package net.ecmoons.feature;

import net.ecmoons.config.Settings;

/**
 * Arcane Moon cost rules, shared by the server (what is charged) and the client (what is displayed).
 * <p>
 * discounted = max(min(original, minLevelCost), round_half_up(original * levelCostMultiplier))
 * <br>With the defaults (0.75, minimum 1): enchanting slots 1/2/3 cost 1/2/2 levels; anvil 4 -> 3, 10 -> 8, 39 -> 29.
 * A zero cost stays zero. The anvil's "Too Expensive" limit (40) is checked against the ORIGINAL cost, so the
 * discount never makes a too-expensive operation possible.
 */
public final class ArcaneCosts {
    private ArcaneCosts() {}

    public static int discount(int original, double multiplier, int minCost) {
        if (original <= 0) return original;
        int reduced = (int) Math.floor(original * multiplier + 0.5);
        int floor = Math.min(original, minCost);
        return Math.max(floor, Math.min(original, reduced));
    }

    public static int discount(Settings s, int original) {
        return discount(original, s.d("arcane.levelCostMultiplier"), s.i("arcane.minLevelCost"));
    }

    /** Durability restored per material unit: vanilla max/4, times the repair bonus, at least 1. */
    public static int repairPerUnit(int maxDamage, double bonus) {
        return Math.max(1, (int) Math.floor((maxDamage / 4) * bonus));
    }
}
