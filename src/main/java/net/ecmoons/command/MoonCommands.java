package net.ecmoons.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import corgitaco.enhancedcelestials.LunarContext;
import corgitaco.enhancedcelestials.LunarEventInstance;
import corgitaco.enhancedcelestials.api.lunarevent.LunarEvent;
import corgitaco.enhancedcelestials.network.NetworkHandler;
import corgitaco.enhancedcelestials.network.packet.LunarForecastChangedPacket;
import corgitaco.enhancedcelestials.save.LunarEventSavedData;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.MoonConfig;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.BlueMoonLoot;
import net.ecmoons.feature.DriftFeatures;
import net.ecmoons.feature.FrostFeatures;
import net.ecmoons.feature.MoonLifecycle;
import net.ecmoons.feature.ProspectorFeatures;
import net.ecmoons.feature.QuietFeatures;
import net.ecmoons.feature.SpawnOrigins;
import net.ecmoons.feature.WisdomFeatures;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.command.arguments.BlockPosArgument;
import net.minecraft.command.arguments.EntityArgument;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.ForgeMod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /ecmoons administrator commands. Every command acts on the dimension the command is run in
 * (use /execute in &lt;dimension&gt; run ecmoons ... for another one).
 */
public final class MoonCommands {
    private MoonCommands() {}

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(Commands.literal("ecmoons")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("list").executes(MoonCommands::list))
                .then(Commands.literal("active").executes(MoonCommands::active))
                .then(Commands.literal("start").requires(src -> src.hasPermission(3))
                        .then(Commands.argument("event", StringArgumentType.word())
                                .suggests((ctx, sb) -> ISuggestionProvider.suggest(eventKeys(ctx.getSource()), sb))
                                .executes(ctx -> start(ctx, true))
                                .then(Commands.literal("now").executes(ctx -> start(ctx, true)))
                                .then(Commands.literal("tonight").executes(ctx -> start(ctx, false)))))
                .then(Commands.literal("end").requires(src -> src.hasPermission(3)).executes(MoonCommands::end))
                .then(Commands.literal("settings")
                        .then(Commands.argument("moon", StringArgumentType.word())
                                .suggests((ctx, sb) -> ISuggestionProvider.suggest(moonIds(), sb))
                                .executes(MoonCommands::settings)))
                .then(Commands.literal("inspect")
                        .then(Commands.literal("player").then(Commands.argument("player", EntityArgument.player()).executes(MoonCommands::inspectPlayer)))
                        .then(Commands.literal("block").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(MoonCommands::inspectBlock)))
                        .then(Commands.literal("entity").then(Commands.argument("target", EntityArgument.entity()).executes(MoonCommands::inspectEntity)))
                        .then(Commands.literal("loot").executes(MoonCommands::inspectLoot)))
                .then(Commands.literal("reload").requires(src -> src.hasPermission(3)).executes(MoonCommands::reload)));
    }

    private static List<String> moonIds() {
        List<String> out = new ArrayList<>();
        for (MoonType t : MoonType.MOONS) out.add(t.id());
        out.add("general");
        return out;
    }

    private static List<String> eventKeys(CommandSource source) {
        LunarContext ctx = Moons.context(source.getLevel());
        return ctx == null ? new ArrayList<>() : new ArrayList<>(ctx.getLunarEvents().keySet());
    }

    private static void line(CommandSource src, IFormattableTextComponent text) {
        src.sendSuccess(text, false);
    }

    private static IFormattableTextComponent t(String s, TextFormatting f) {
        return new StringTextComponent(s).withStyle(f);
    }

    private static LunarContext requireContext(CommandSource src) {
        LunarContext ctx = Moons.context(src.getLevel());
        if (ctx == null) src.sendFailure(new StringTextComponent("Enhanced Celestials has no lunar context in "
                + src.getLevel().dimension().location() + " (lunar events only exist in overworld-like dimensions)."));
        return ctx;
    }

    private static int list(CommandContext<CommandSource> c) {
        CommandSource src = c.getSource();
        LunarContext ctx = requireContext(src);
        if (ctx == null) return 0;
        Settings s = Settings.server();
        line(src, t("Lunar events in " + src.getLevel().dimension().location() + ":", TextFormatting.GOLD));
        for (Map.Entry<String, LunarEvent> e : ctx.getLunarEvents().entrySet()) {
            LunarEvent ev = e.getValue();
            MoonType type = MoonType.of(ev);
            boolean sup = MoonType.isSuperVariant(ev);
            String effects = type == MoonType.NONE ? "Enhanced Celestials only"
                    : !s.moonEnabled(type) ? "add-on effects disabled"
                    : sup && !s.superInherits() ? "Super: Enhanced Celestials behaviour only"
                    : !s.dimensionAllowed(type, src.getLevel().dimension().location()) ? "add-on effects off in this dimension"
                    : sup ? "Super: inherits " + type.id() + " add-on features (same strength)" : type.id() + " add-on features";
            line(src, t(" " + e.getKey(), TextFormatting.AQUA)
                    .append(t(String.format("  chance %.4f, min nights %d, phases %s, sleep %s - %s",
                            ev.getChance(), ev.getMinNumberOfNightsBetween(), ev.getValidMoonPhases(),
                            ev.blockSleeping() ? "blocked" : "allowed", effects), TextFormatting.GRAY)));
        }
        return ctx.getLunarEvents().size();
    }

    private static int active(CommandContext<CommandSource> c) {
        CommandSource src = c.getSource();
        ServerWorld world = src.getLevel();
        LunarContext ctx = requireContext(src);
        if (ctx == null) return 0;
        LunarEvent ev = ctx.getCurrentEvent();
        MoonType effects = Moons.active(world);
        long dayLength = ctx.getLunarTimeSettings().getDayLength();
        long day = world.getDayTime() / dayLength;
        line(src, t("Current lunar event: ", TextFormatting.GOLD).append(t(String.valueOf(ev.getKey()), TextFormatting.AQUA))
                .append(t("  (add-on effects: " + effects.id() + ", super: " + MoonType.isSuperVariant(ev)
                        + ", night: " + world.isNight() + ", fade " + Math.round(ctx.getStrength() * 100) + "%)", TextFormatting.GRAY)));
        List<LunarEventInstance> forecast = ctx.getLunarForecast().getForecast();
        StringBuilder next = new StringBuilder();
        for (int i = 0; i < Math.min(5, forecast.size()); i++) {
            LunarEventInstance inst = forecast.get(i);
            if (next.length() > 0) next.append(", ");
            next.append(inst.getLunarEventKey()).append(" in ").append(inst.getDaysUntil(day)).append("d");
        }
        line(src, t("Next: " + (next.length() == 0 ? "none" : next.toString()), TextFormatting.GRAY));
        return 1;
    }

    private static int start(CommandContext<CommandSource> c, boolean now) {
        CommandSource src = c.getSource();
        ServerWorld world = src.getLevel();
        LunarContext ctx = requireContext(src);
        if (ctx == null) return 0;
        String key = StringArgumentType.getString(c, "event");
        if (!ctx.getLunarEvents().containsKey(key)) {
            src.sendFailure(new StringTextComponent("Unknown or disabled lunar event '" + key + "'. Use /ecmoons list."));
            return 0;
        }
        long dayLength = ctx.getLunarTimeSettings().getDayLength();
        long day = world.getDayTime() / dayLength;
        List<LunarEventInstance> forecast = ctx.getLunarForecast().getForecast();
        while (!forecast.isEmpty() && forecast.get(0).getDaysUntil(day) <= 0) forecast.remove(0);
        forecast.add(0, new LunarEventInstance(key, day));
        if (now && !world.isNight()) world.setDayTime(day * dayLength + 13000L);
        saveAndSync(world, ctx);
        src.sendSuccess(new StringTextComponent(now
                ? "Started '" + key + "' (Enhanced Celestials switches it on within a tick; time set to night if it was day)."
                : "Scheduled '" + key + "' for tonight (no time change)."), true);
        return 1;
    }

    private static int end(CommandContext<CommandSource> c) {
        CommandSource src = c.getSource();
        ServerWorld world = src.getLevel();
        LunarContext ctx = requireContext(src);
        if (ctx == null) return 0;
        long day = world.getDayTime() / ctx.getLunarTimeSettings().getDayLength();
        List<LunarEventInstance> forecast = ctx.getLunarForecast().getForecast();
        int removed = 0;
        while (!forecast.isEmpty() && forecast.get(0).getDaysUntil(day) <= 0) {
            forecast.remove(0);
            removed++;
        }
        if (removed == 0) {
            src.sendFailure(new StringTextComponent("No lunar event is scheduled for tonight in this dimension."));
            return 0;
        }
        saveAndSync(world, ctx);
        src.sendSuccess(new StringTextComponent("Ended tonight's lunar event. Enhanced Celestials returns to the ordinary moon on the next tick, "
                + "sends its set notification, and the add-on removes its modifiers and visuals."), true);
        return 1;
    }

    private static void saveAndSync(ServerWorld world, LunarContext ctx) {
        LunarEventSavedData.get(world).setForecast(ctx.getLunarForecast());
        NetworkHandler.sendToAllPlayers(world.players(), new LunarForecastChangedPacket(ctx.getLunarForecast()));
    }

    private static int settings(CommandContext<CommandSource> c) {
        CommandSource src = c.getSource();
        String moon = StringArgumentType.getString(c, "moon").toLowerCase();
        Settings s = Settings.server();
        line(src, t("ecmoons settings: " + moon, TextFormatting.GOLD));
        int n = 0;
        for (String key : MoonConfig.VALUES.keySet()) {
            if (!key.startsWith(moon + ".")) continue;
            Object v;
            try {
                v = s.list(key);
            } catch (ClassCastException e) {
                v = s.s(key);
            }
            line(src, t(" " + key.substring(moon.length() + 1) + " = ", TextFormatting.AQUA).append(t(String.valueOf(v), TextFormatting.WHITE)));
            n++;
        }
        if (n == 0) src.sendFailure(new StringTextComponent("Unknown moon '" + moon + "'."));
        return n;
    }

    private static int inspectPlayer(CommandContext<CommandSource> c) throws CommandSyntaxException {
        CommandSource src = c.getSource();
        ServerPlayerEntity p = EntityArgument.getPlayer(c, "player");
        FrostFeatures.State fs = FrostFeatures.state(p);
        long now = p.level.getGameTime();
        line(src, t("Player " + p.getScoreboardName() + " in " + p.level.dimension().location(), TextFormatting.GOLD));
        line(src, t(" moon effects: " + Moons.active(p.level).id() + " | luck: " + p.getLuck(), TextFormatting.GRAY));
        line(src, t(" frost: exposure " + fs.exposureTicks() / 20 + "s, chilled " + fs.chilled()
                + ", warmth left " + Math.max(0, (fs.warmUntil() - now) / 20) + "s, insulating pieces "
                + FrostFeatures.insulatingPieces(p, Settings.server()), TextFormatting.GRAY));
        long since = now - QuietFeatures.lastCombat(p.getUUID());
        line(src, t(" quiet: last combat " + (since > 1_000_000_000L ? "never" : since / 20 + "s ago")
                + ", food " + p.getFoodData().getFoodLevel(), TextFormatting.GRAY));
        line(src, t(" drift: airborne grace " + DriftFeatures.hasGrace(p) + " | nametag distance "
                + p.getAttributeValue(ForgeMod.NAMETAG_DISTANCE.get()), TextFormatting.GRAY));
        return 1;
    }

    private static int inspectBlock(CommandContext<CommandSource> c) throws CommandSyntaxException {
        CommandSource src = c.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
        ServerWorld world = src.getLevel();
        line(src, t("Block " + world.getBlockState(pos).getBlock().getRegistryName() + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ(), TextFormatting.GOLD));
        for (Map.Entry<String, String> e : ProspectorFeatures.describe(world, pos).entrySet()) {
            line(src, t(" " + e.getKey() + ": ", TextFormatting.AQUA).append(t(e.getValue(), TextFormatting.WHITE)));
        }
        return 1;
    }

    private static int inspectEntity(CommandContext<CommandSource> c) throws CommandSyntaxException {
        CommandSource src = c.getSource();
        Entity e = EntityArgument.getEntity(c, "target");
        Settings s = Settings.server();
        String origin = SpawnOrigins.origin(e);
        boolean hostile = e instanceof LivingEntity && WisdomFeatures.isHostile((LivingEntity) e);
        line(src, t("Entity " + e.getType().getRegistryName() + " (" + e.getUUID() + ")", TextFormatting.GOLD));
        line(src, t(" spawn origin: " + (origin == null ? "untracked" : origin) + ", hostile: " + hostile, TextFormatting.GRAY));
        line(src, t(" Wisdom XP eligible: " + (hostile && SpawnOrigins.eligible(e, s.list("wisdom.eligibleSpawnOrigins"), s.b("wisdom.untrackedMobsEligible")))
                + " | Blood rare-drop eligible: " + (hostile && SpawnOrigins.eligible(e, s.list("blood.eligibleSpawnOrigins"), s.b("blood.untrackedMobsEligible"))),
                TextFormatting.GRAY));
        return 1;
    }

    private static int inspectLoot(CommandContext<CommandSource> c) {
        CommandSource src = c.getSource();
        Map<ResourceLocation, Integer> tables = BlueMoonLoot.injectedTables();
        line(src, t("Blue Moon bonus pools (" + BlueMoonLoot.file() + "):", TextFormatting.GOLD));
        if (tables.isEmpty()) line(src, t(" none injected", TextFormatting.GRAY));
        for (Map.Entry<ResourceLocation, Integer> e : tables.entrySet()) {
            line(src, t(" " + e.getKey() + ": up to " + e.getValue() + " Luck-gated bonus roll(s)", TextFormatting.GRAY));
        }
        return tables.size();
    }

    private static int reload(CommandContext<CommandSource> c) {
        MoonLifecycle.settingsChanged(c.getSource().getServer().getAllLevels());
        c.getSource().sendSuccess(new StringTextComponent("ecmoons settings re-read and re-sent to all players. "
                + "Blue Moon loot changes apply on /reload; enabling/disabling moons applies on restart."), true);
        return 1;
    }
}
