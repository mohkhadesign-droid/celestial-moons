package net.ecmoons.feature;

import com.google.gson.JsonObject;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Matchers;
import net.ecmoons.config.Settings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootContext;
import net.minecraft.loot.LootParameters;
import net.minecraft.loot.conditions.ILootCondition;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.loot.GlobalLootModifierSerializer;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Blood Moon rare drops: raises the chance of configured rare drops by a multiplicative factor (default x1.25,
 * so 4% becomes 5%) without re-rolling or duplicating the mob's loot table.
 * <p>
 * For a rule with per-kill chance p (base + looting bonus), if none of its items dropped we add one with
 * probability q = (m - 1) p / (1 - p). Then P(drop) = p + (1 - p) q = m p exactly.
 */
public final class BloodMoonDrops {
    private BloodMoonDrops() {}

    public static final class Rule {
        final String entity;
        final List<Item> items;
        final double base;
        final double perLooting;

        Rule(String entity, List<Item> items, double base, double perLooting) {
            this.entity = entity;
            this.items = items;
            this.base = base;
            this.perLooting = perLooting;
        }
    }

    private static Settings cachedFor;
    private static List<Rule> cachedRules = Collections.emptyList();

    public static synchronized List<Rule> rules() {
        Settings s = Settings.server();
        if (s == cachedFor) return cachedRules;
        List<Rule> out = new ArrayList<>();
        for (String raw : s.list("blood.rareDropRules")) {
            String[] parts = raw.split("\\|");
            if (parts.length != 4) {
                EcMoons.LOGGER.warn("Ignoring Blood Moon rule (expected entity|items|baseChance|perLooting): {}", raw);
                continue;
            }
            try {
                List<Item> items = new ArrayList<>();
                for (String id : parts[1].split(",")) {
                    ResourceLocation rl = new ResourceLocation(id.trim());
                    if (ForgeRegistries.ITEMS.containsKey(rl)) items.add(ForgeRegistries.ITEMS.getValue(rl));
                }
                if (items.isEmpty()) {
                    EcMoons.LOGGER.warn("Ignoring Blood Moon rule with no known items: {}", raw);
                    continue;
                }
                out.add(new Rule(parts[0].trim(), items, Double.parseDouble(parts[2].trim()), Double.parseDouble(parts[3].trim())));
            } catch (RuntimeException e) {
                EcMoons.LOGGER.warn("Ignoring invalid Blood Moon rule '{}': {}", raw, e.getMessage());
            }
        }
        cachedRules = out;
        cachedFor = s;
        return out;
    }

    /** Second-chance probability so that the overall chance becomes m * p (capped at 1). */
    public static double secondChance(double p, double m) {
        if (p <= 0) return 0;
        if (p * m >= 1 || p >= 1) return 1;
        return (m - 1) * p / (1 - p);
    }

    public static class Modifier extends LootModifier {
        public Modifier(ILootCondition[] conditions) {
            super(conditions);
        }

        ILootCondition[] lootConditions() {
            return conditions;
        }

        @Nonnull
        @Override
        protected List<ItemStack> doApply(List<ItemStack> generatedLoot, LootContext context) {
            Entity victim = context.getParamOrNull(LootParameters.THIS_ENTITY);
            if (!(victim instanceof LivingEntity) || victim instanceof PlayerEntity) return generatedLoot;
            if (!context.hasParam(LootParameters.DAMAGE_SOURCE)) return generatedLoot; // death drops only
            if (!Moons.is(context.getLevel(), MoonType.BLOOD)) return generatedLoot;
            Settings s = Settings.server();
            if (!WisdomFeatures.isHostile((LivingEntity) victim)) return generatedLoot;
            if (s.b("blood.requirePlayerKill")) {
                PlayerEntity killer = context.getParamOrNull(LootParameters.LAST_DAMAGE_PLAYER);
                if (!ProspectorFeatures.isManualMiner(killer)) return generatedLoot;
            }
            if (!SpawnOrigins.eligible(victim, s.list("blood.eligibleSpawnOrigins"), s.b("blood.untrackedMobsEligible"))) {
                return generatedLoot;
            }
            double m = s.d("blood.rareDropMultiplier");
            int looting = context.getLootingModifier();
            for (Rule rule : rules()) {
                if (!Matchers.entity(s, rule.entity, victim.getType())) continue;
                boolean dropped = false;
                for (ItemStack stack : generatedLoot) {
                    if (rule.items.contains(stack.getItem())) {
                        dropped = true;
                        break;
                    }
                }
                if (dropped) continue;
                double p = Math.min(1.0, rule.base + rule.perLooting * looting);
                if (context.getRandom().nextDouble() < secondChance(p, m)) {
                    Item item = rule.items.get(context.getRandom().nextInt(rule.items.size()));
                    generatedLoot.add(new ItemStack(item));
                }
            }
            return generatedLoot;
        }
    }

    public static class Serializer extends GlobalLootModifierSerializer<Modifier> {
        @Override
        public Modifier read(ResourceLocation location, JsonObject object, ILootCondition[] conditions) {
            return new Modifier(conditions);
        }

        @Override
        public JsonObject write(Modifier instance) {
            return makeConditions(instance.lootConditions());
        }
    }
}
