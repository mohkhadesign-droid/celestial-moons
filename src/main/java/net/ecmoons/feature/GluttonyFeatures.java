package net.ecmoons.feature;

import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Matchers;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.war.WarFeatures;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.entity.ai.attributes.ModifiableAttributeInstance;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Gluttony Moon: each different food category eaten during the night adds one temporary heart (max 4 by default).
 * <p>
 * The bonus is a single transient max-health modifier owned by this event (fixed UUID). It is never written to the
 * player file; the eaten categories are kept in the player's persistent data together with the night they belong
 * to, so the bonus survives relogs during the same Gluttony night and is gone afterwards. Adding hearts never
 * heals. When the bonus is removed (moon ends, death, leaving the moon's dimension, War Moon match) health is
 * clamped to the new maximum.
 */
public final class GluttonyFeatures {
    public static final UUID MODIFIER = UUID.fromString("6a1f0b0e-93f4-4c7c-b2a1-5d2f6e8c4a11");
    public static final String KEY = "ecmoons_gluttony";

    private GluttonyFeatures() {}

    private static long night(ServerPlayerEntity p) {
        return p.server.overworld().getDayTime() / 24000L;
    }

    /** Category this food counts as, or null when it never counts. */
    public static String category(Item item) {
        Settings s = Settings.server();
        if (Matchers.item(s, "gluttony.excludedFoods", item)) return null;
        for (String entry : s.list("gluttony.foodCategories")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String name = entry.substring(0, eq).trim();
            java.util.List<String> items = java.util.Arrays.asList(entry.substring(eq + 1).split(","));
            if (Matchers.itemIn(s, "gluttony:" + name, items, item)) return name;
        }
        return String.valueOf(ForgeRegistries.ITEMS.getKey(item));
    }

    /** Categories eaten this Gluttony night (empty if the stored progress is from another night). */
    public static Set<String> eaten(ServerPlayerEntity p) {
        Set<String> out = new LinkedHashSet<>();
        CompoundNBT tag = p.getPersistentData().getCompound(KEY);
        if (tag.getLong("night") != night(p)) return out;
        ListNBT list = tag.getList("eaten", Constants.NBT.TAG_STRING);
        for (int i = 0; i < list.size(); i++) out.add(list.getString(i));
        return out;
    }

    private static void store(ServerPlayerEntity p, Set<String> eaten) {
        CompoundNBT tag = new CompoundNBT();
        tag.putLong("night", night(p));
        ListNBT list = new ListNBT();
        for (String s : eaten) list.add(StringNBT.valueOf(s));
        tag.put("eaten", list);
        p.getPersistentData().put(KEY, tag);
    }

    public static int hearts(ServerPlayerEntity p) {
        return Math.min(eaten(p).size(), Settings.server().i("gluttony.maxBonusHearts"));
    }

    public static void onAte(ServerPlayerEntity p, ItemStack stack) {
        if (WarFeatures.isInvolved(p)) return;
        String cat = category(stack.getItem());
        int max = Settings.server().i("gluttony.maxBonusHearts");
        if (cat == null) {
            p.displayClientMessage(new StringTextComponent("Gluttony: this food does not count").withStyle(TextFormatting.GRAY), true);
            return;
        }
        Set<String> eaten = eaten(p);
        if (eaten.size() >= max) {
            if (!eaten.contains(cat)) { // a new food, but no heart left to give: the small burst only
                net.ecmoons.net.Network.CHANNEL.send(net.minecraftforge.fml.network.PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> p),
                        new net.ecmoons.net.AtmoPacket(net.ecmoons.net.AtmoPacket.GLUTTONY, p.getId(), 0, 0));
            }
            p.displayClientMessage(new StringTextComponent("Gluttony: all " + max + " bonus hearts gained").withStyle(TextFormatting.GOLD), true);
            return;
        }
        if (!eaten.add(cat)) {
            p.displayClientMessage(new StringTextComponent("Gluttony: " + pretty(cat) + " already counted (" + Math.min(eaten.size(), max) + "/" + max
                    + " bonus hearts)").withStyle(TextFormatting.GRAY), true);
            return;
        }
        store(p, eaten);
        apply(p, Math.min(eaten.size(), max));
        // visual only: nearby clients show a honey-gold burst (a stronger ring for a new heart)
        net.ecmoons.net.Network.CHANNEL.send(net.minecraftforge.fml.network.PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> p),
                new net.ecmoons.net.AtmoPacket(net.ecmoons.net.AtmoPacket.GLUTTONY, p.getId(), 1, 0));
        p.displayClientMessage(new StringTextComponent("Gluttony: " + Math.min(eaten.size(), max) + "/" + max + " bonus hearts")
                .withStyle(TextFormatting.YELLOW), true);
    }

    private static String pretty(String cat) {
        int colon = cat.indexOf(':');
        return (colon >= 0 ? cat.substring(colon + 1) : cat).replace('_', ' ');
    }

    /** Make the modifier match the given number of hearts (0 removes it). Never heals; clamps when lowering. */
    public static void apply(ServerPlayerEntity p, int hearts) {
        ModifiableAttributeInstance attr = p.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;
        AttributeModifier current = attr.getModifier(MODIFIER);
        double amount = hearts * 2.0;
        if (current != null && current.getAmount() == amount) return;
        if (current != null) attr.removeModifier(MODIFIER);
        if (hearts > 0) attr.addTransientModifier(new AttributeModifier(MODIFIER, "ecmoons gluttony", amount, AttributeModifier.Operation.ADDITION));
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    /** Once a second per player: the bonus exists only while the moon is up in the player's world. */
    public static void tick(ServerPlayerEntity p) {
        boolean active = Moons.is(p.level, MoonType.GLUTTONY) && !WarFeatures.isInvolved(p) && p.isAlive();
        apply(p, active ? hearts(p) : 0);
    }

    public static void onDeath(ServerPlayerEntity p) {
        p.getPersistentData().remove(KEY);
        apply(p, 0);
    }

    public static void reset(ServerPlayerEntity p) {
        onDeath(p);
    }

    public static String describe(ServerPlayerEntity p) {
        List<String> list = new java.util.ArrayList<>(eaten(p));
        return p.getScoreboardName() + ": " + hearts(p) + "/" + Settings.server().i("gluttony.maxBonusHearts") + " bonus hearts, counted "
                + (list.isEmpty() ? "nothing" : String.join(", ", list)) + ", max health " + p.getMaxHealth();
    }
}
