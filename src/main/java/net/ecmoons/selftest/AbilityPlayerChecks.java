package net.ecmoons.selftest;

import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.feature.ability.AfterimageAbility;
import net.ecmoons.feature.ability.MoonAbilities;
import net.ecmoons.feature.ability.ShellAbility;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;

/** Server-side reads for the client test of the Moon Ability key (integrated server thread). */
public final class AbilityPlayerChecks {
    private static final String P = "[ECMOONS-CLIENTTEST] ";
    public static volatile int stage, cooldown, shellCooldown, bonusHits;
    public static volatile boolean bonus, penalised, shell, charging;
    public static volatile double x, y, z, step1, step2, step3;
    public static volatile float health, knockback;

    private AbilityPlayerChecks() {}

    /** Snapshot the player's ability state (read by the client test a tick later). */
    public static void snapshot(MinecraftServer server) {
        ServerPlayerEntity p = server.getPlayerList().getPlayers().get(0);
        stage = AfterimageAbility.stage(p);
        bonus = AfterimageAbility.hasBonus(p);
        penalised = AfterimageAbility.penalised(p);
        cooldown = MoonAbilities.cooldownLeft(p, MoonType.AFTERIMAGE);
        shellCooldown = MoonAbilities.cooldownLeft(p, MoonType.SHELL);
        shell = ShellAbility.hasShell(p);
        charging = ShellAbility.charging(p);
        bonusHits = AfterimageAbility.bonusHits;
        step1 = AfterimageAbility.LAST_STEPS[1];
        step2 = AfterimageAbility.LAST_STEPS[2];
        step3 = AfterimageAbility.LAST_STEPS[3];
        knockback = AfterimageAbility.lastKnockback;
        health = p.getHealth();
        x = p.getX();
        y = p.getY();
        z = p.getZ();
        EcMoons.LOGGER.info(P + "ability state: stage " + stage + " bonus " + bonus + " penalised " + penalised + " cd " + cooldown
                + " shell " + shell + " charging " + charging + " shellCd " + shellCooldown + " bonusHits " + bonusHits + " steps " + step1 + "/" + step2 + "/" + step3 + " kb " + knockback + " health " + health + " pos " + x + "," + y + "," + z);
    }

    /** Full health, no absorption, Resistance II for the explosion comparison (same for the control and shell runs). */
    public static void prepareExplosion(MinecraftServer server) {
        ServerPlayerEntity p = server.getPlayerList().getPlayers().get(0);
        p.setHealth(p.getMaxHealth());
        p.setAbsorptionAmount(0);
        p.addEffect(new net.minecraft.potion.EffectInstance(net.minecraft.potion.Effects.DAMAGE_RESISTANCE, 1200, 1, false, false));
        p.addEffect(new net.minecraft.potion.EffectInstance(net.minecraft.potion.Effects.FIRE_RESISTANCE, 1200, 0, false, false));
        health = p.getHealth();
    }
}
