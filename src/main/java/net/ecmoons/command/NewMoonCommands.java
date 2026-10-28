package net.ecmoons.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.ecmoons.feature.GluttonyFeatures;
import net.ecmoons.feature.MoonTeams;
import net.ecmoons.feature.RevealingFeatures;
import net.ecmoons.feature.ThreadFeatures;
import net.ecmoons.feature.war.WarArena;
import net.ecmoons.feature.war.WarFeatures;
import net.ecmoons.feature.war.WarRecords;
import net.ecmoons.feature.war.WarState;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.command.arguments.EntityArgument;
import net.minecraft.command.arguments.GameProfileArgument;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.server.ServerWorld;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Commands for the Revealing, Thread, War and Gluttony moons, plus /moonteams and the player-level /warmoon.
 * Starting and ending any moon is the existing /ecmoons start &lt;event&gt; now and /ecmoons end.
 */
public final class NewMoonCommands {
    private NewMoonCommands() {}

    private static void ok(CommandSource src, String msg) {
        src.sendSuccess(new StringTextComponent(msg), true);
    }

    private static void info(CommandSource src, String msg) {
        src.sendSuccess(new StringTextComponent(msg), false);
    }

    private static int fail(CommandSource src, String msg) {
        src.sendFailure(new StringTextComponent(msg));
        return 0;
    }

    public static void register(CommandDispatcher<CommandSource> d) {
        d.register(Commands.literal("ecmoons").requires(s -> s.hasPermission(2))
                .then(Commands.literal("reveal")
                        .then(Commands.literal("scan").executes(c -> revealScan(c, false))
                                .then(Commands.literal("self").executes(c -> revealScan(c, true))))
                        .then(Commands.literal("warn").executes(NewMoonCommands::revealWarn))
                        .then(Commands.literal("blink").executes(NewMoonCommands::revealBlink))
                        .then(Commands.literal("status").executes(c -> {
                            info(c.getSource(), "Revealing Moon: " + RevealingFeatures.describe(c.getSource().getLevel()));
                            return 1;
                        })))
                .then(Commands.literal("thread")
                        .then(Commands.literal("status").executes(c -> threadStatus(c, c.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(c -> threadStatus(c, EntityArgument.getPlayer(c, "player"))))))
                .then(Commands.literal("gluttony")
                        .then(Commands.literal("status").executes(c -> gluttonyStatus(c, c.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(c -> gluttonyStatus(c, EntityArgument.getPlayer(c, "player")))))
                        .then(Commands.literal("reset").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                            ServerPlayerEntity p = EntityArgument.getPlayer(c, "player");
                            GluttonyFeatures.reset(p);
                            ok(c.getSource(), "Gluttony progress reset for " + p.getScoreboardName());
                            return 1;
                        }))))
                .then(Commands.literal("war")
                        .then(Commands.literal("status").executes(NewMoonCommands::warStatus))
                        .then(Commands.literal("start").requires(s -> s.hasPermission(3))
                                .executes(c -> warStart(c, false, false))
                                .then(Commands.literal("now").executes(c -> warStart(c, true, false)))
                                .then(Commands.literal("solo").executes(c -> warStart(c, true, true))))
                        .then(Commands.literal("cancel").requires(s -> s.hasPermission(3)).executes(c -> {
                            WarFeatures.cancel(c.getSource().getServer(), "stopped by an administrator");
                            ok(c.getSource(), "War Moon tournament: " + WarFeatures.describe(c.getSource().getServer()));
                            return 1;
                        }))
                        .then(Commands.literal("records").executes(NewMoonCommands::warRecords))
                        .then(Commands.literal("recover").requires(s -> s.hasPermission(3))
                                .then(Commands.argument("player", EntityArgument.player()).executes(NewMoonCommands::warRecover)))
                        .then(Commands.literal("exclude").then(Commands.argument("player", EntityArgument.players())
                                .executes(c -> warTag(c, WarFeatures.EXEMPT_TAG, true, "excluded from"))))
                        .then(Commands.literal("include").then(Commands.argument("player", EntityArgument.players())
                                .executes(c -> warTag(c, WarFeatures.EXEMPT_TAG, false, "eligible again for"))))
                        .then(Commands.literal("spectate").then(Commands.argument("player", EntityArgument.players())
                                .executes(c -> warTag(c, WarFeatures.SPECTATE_TAG, true, "will spectate"))
                                .then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> warTag(c, WarFeatures.SPECTATE_TAG,
                                        BoolArgumentType.getBool(c, "on"), BoolArgumentType.getBool(c, "on") ? "will spectate" : "will no longer spectate")))))
                        .then(Commands.literal("champion").requires(s -> s.hasPermission(3))
                                .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                                    ServerPlayerEntity p = EntityArgument.getPlayer(c, "player");
                                    WarFeatures.setChampionTicks(p, net.ecmoons.config.Settings.server().i("war.championMinutes") * 1200);
                                    ok(c.getSource(), p.getScoreboardName() + " is War Champion (test grant)");
                                    return 1;
                                }).then(Commands.literal("clear").executes(c -> {
                                    ServerPlayerEntity p = EntityArgument.getPlayer(c, "player");
                                    WarFeatures.clearChampion(p);
                                    ok(c.getSource(), "War Champion reward removed from " + p.getScoreboardName());
                                    return 1;
                                }))))
                        .then(Commands.literal("arena").requires(s -> s.hasPermission(3))
                                .then(Commands.literal("build").executes(c -> warArena(c, true)))
                                .then(Commands.literal("clear").executes(c -> warArena(c, false))))));

        d.register(Commands.literal("ecmoons").requires(s -> s.hasPermission(2))
                .then(Commands.literal("atmosphere")
                        .then(Commands.literal("preview")
                                .then(Commands.argument("moon", StringArgumentType.word())
                                        .suggests((c, sb) -> ISuggestionProvider.suggest(previewIds(), sb))
                                        .executes(c -> preview(c, java.util.Collections.singletonList(c.getSource().getPlayerOrException())))
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(c -> preview(c, EntityArgument.getPlayers(c, "targets"))))))
                        .then(Commands.literal("demo")
                                .then(Commands.argument("effect", StringArgumentType.word())
                                        .suggests((c, sb) -> ISuggestionProvider.suggest(java.util.Arrays.asList("reveal", "war", "feast"), sb))
                                        .executes(c -> demo(c, java.util.Collections.singletonList(c.getSource().getPlayerOrException())))
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(c -> demo(c, EntityArgument.getPlayers(c, "targets"))))))));

        d.register(Commands.literal("moonteams").requires(s -> s.hasPermission(2))
                .then(Commands.literal("create").then(Commands.argument("teamId", StringArgumentType.word())
                        .then(Commands.argument("displayName", StringArgumentType.greedyString()).executes(c -> {
                            String id = StringArgumentType.getString(c, "teamId"), name = StringArgumentType.getString(c, "displayName");
                            if (!MoonTeams.get(c.getSource().getServer()).create(id, name)) return fail(c.getSource(), "Team " + id + " already exists");
                            ok(c.getSource(), "Created moon team " + id + " (" + name + ")");
                            return 1;
                        }))))
                .then(Commands.literal("delete").then(Commands.argument("teamId", StringArgumentType.word()).suggests(NewMoonCommands::teamIds)
                        .executes(c -> {
                            String id = StringArgumentType.getString(c, "teamId");
                            if (!MoonTeams.get(c.getSource().getServer()).delete(id)) return fail(c.getSource(), "No team " + id);
                            ok(c.getSource(), "Deleted moon team " + id + " (its members now have no team)");
                            return 1;
                        })))
                .then(Commands.literal("assign").then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .then(Commands.argument("teamId", StringArgumentType.word()).suggests(NewMoonCommands::teamIds).executes(c -> {
                            String id = StringArgumentType.getString(c, "teamId");
                            MoonTeams teams = MoonTeams.get(c.getSource().getServer());
                            int n = 0;
                            for (GameProfile gp : GameProfileArgument.getGameProfiles(c, "player")) {
                                if (!teams.assign(gp.getId(), gp.getName(), id)) return fail(c.getSource(), "No team " + id + " (create it first)");
                                ok(c.getSource(), gp.getName() + " is now in moon team " + id + " (" + teams.displayName(id) + ")");
                                n++;
                            }
                            return n;
                        }))))
                .then(Commands.literal("remove").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(c -> {
                    MoonTeams teams = MoonTeams.get(c.getSource().getServer());
                    int n = 0;
                    for (GameProfile gp : GameProfileArgument.getGameProfiles(c, "player")) {
                        if (teams.remove(gp.getId())) {
                            ok(c.getSource(), gp.getName() + " removed from their moon team");
                            n++;
                        } else {
                            info(c.getSource(), gp.getName() + " has no moon team");
                        }
                    }
                    return n;
                })))
                .then(Commands.literal("list").executes(c -> {
                    MoonTeams teams = MoonTeams.get(c.getSource().getServer());
                    if (teams.teams().isEmpty()) info(c.getSource(), "No moon teams. Create one with /moonteams create <teamId> <displayName>");
                    for (Map.Entry<String, String> e : teams.teams().entrySet()) {
                        info(c.getSource(), e.getKey() + " - " + e.getValue() + " (" + teams.membersOf(e.getKey()).size() + " members)");
                    }
                    return teams.teams().size();
                }))
                .then(Commands.literal("info").then(Commands.argument("teamId", StringArgumentType.word()).suggests(NewMoonCommands::teamIds)
                        .executes(c -> {
                            String id = StringArgumentType.getString(c, "teamId");
                            MoonTeams teams = MoonTeams.get(c.getSource().getServer());
                            if (teams.displayName(id) == null) return fail(c.getSource(), "No team " + id);
                            Map<UUID, String> members = teams.membersOf(id);
                            info(c.getSource(), id + " - " + teams.displayName(id) + ": " + (members.isEmpty() ? "no members" : String.join(", ", members.values())));
                            return members.size();
                        }))));

        // Player-level: opt in or out of watching War Moon battles.
        d.register(Commands.literal("warmoon")
                .then(Commands.literal("spectate").executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrException();
                    boolean on = !p.getTags().contains(WarFeatures.SPECTATE_TAG);
                    if (on) p.addTag(WarFeatures.SPECTATE_TAG);
                    else p.removeTag(WarFeatures.SPECTATE_TAG);
                    info(c.getSource(), on ? "You will watch the next War Moon battle from the gallery instead of fighting."
                            : "You will fight in the next War Moon battle again.");
                    return 1;
                }))
                .then(Commands.literal("status").executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrException();
                    int t = WarFeatures.championTicks(p);
                    info(c.getSource(), "War Moon: " + WarFeatures.phase(c.getSource().getServer()).name().toLowerCase()
                            + (t > 0 ? ". You are War Champion for " + (t / 1200 + 1) + " more minute(s) of online time." : "."));
                    return 1;
                })));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> teamIds(
            CommandContext<CommandSource> c, com.mojang.brigadier.suggestion.SuggestionsBuilder sb) {
        return ISuggestionProvider.suggest(MoonTeams.get(c.getSource().getServer()).teams().keySet(), sb);
    }

    // ---- Atmosphere preview (visual only) -------------------------------------------------------------------

    private static java.util.List<String> previewIds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (net.ecmoons.MoonType t : net.ecmoons.MoonType.MOONS) out.add(t.id());
        out.add("off");
        return out;
    }

    private static int preview(CommandContext<CommandSource> c, Collection<ServerPlayerEntity> targets) {
        String id = StringArgumentType.getString(c, "moon");
        net.ecmoons.MoonType type = id.equals("off") ? null : net.ecmoons.MoonType.byId(id);
        if (!id.equals("off") && (type == null || type == net.ecmoons.MoonType.NONE)) return fail(c.getSource(), "Unknown moon " + id);
        for (ServerPlayerEntity p : targets) {
            net.ecmoons.net.Network.send(p, new net.ecmoons.net.AtmoPacket(net.ecmoons.net.AtmoPacket.PREVIEW, type == null ? -1 : type.ordinal(), 0, 0));
        }
        ok(c.getSource(), type == null ? "Atmosphere preview off (the real moon's atmosphere returns)"
                : "Previewing the " + type.id() + " atmosphere for " + targets.size() + " player(s). Visual only: the actual lunar event,"
                + " its gameplay and the forecast are unchanged. Best seen at night (/time set 18000). /ecmoons atmosphere preview off to stop.");
        return targets.size();
    }

    private static int demo(CommandContext<CommandSource> c, Collection<ServerPlayerEntity> targets) {
        String e = StringArgumentType.getString(c, "effect");
        int kind = e.equals("reveal") ? net.ecmoons.net.AtmoPacket.DEMO_REVEAL : e.equals("war") ? net.ecmoons.net.AtmoPacket.DEMO_WAR
                : e.equals("feast") ? net.ecmoons.net.AtmoPacket.DEMO_FEAST : -1;
        if (kind < 0) return fail(c.getSource(), "Unknown demo " + e + " (reveal, war, feast)");
        for (ServerPlayerEntity p : targets) net.ecmoons.net.Network.send(p, new net.ecmoons.net.AtmoPacket(net.ecmoons.net.AtmoPacket.DEMO, kind, 0, 0));
        ok(c.getSource(), "Playing the " + e + " atmosphere animation for " + targets.size() + " player(s) (visual only, nothing is scanned,"
                + " summoned or counted)");
        return targets.size();
    }

    // ---- Revealing --------------------------------------------------------------------------------------------

    private static int revealScan(CommandContext<CommandSource> c, boolean self) throws CommandSyntaxException {
        ServerWorld w = c.getSource().getLevel();
        ServerPlayerEntity me = self ? c.getSource().getPlayerOrException() : null;
        int n = RevealingFeatures.scan(w, false, me);
        ok(c.getSource(), "Revealing scan in " + w.dimension().location() + ": " + n + " player(s) revealed"
                + (self ? " (including you)" : ""));
        return n;
    }

    private static int revealWarn(CommandContext<CommandSource> c) {
        if (!RevealingFeatures.warnNow(c.getSource().getLevel())) {
            return fail(c.getSource(), "The Revealing Moon is not active here (start it with /ecmoons start ecmoons-revealing_moon now)");
        }
        ok(c.getSource(), "The eye starts searching now (warning, countdown, scan)");
        return 1;
    }

    private static int revealBlink(CommandContext<CommandSource> c) throws CommandSyntaxException {
        RevealingFeatures.blink(c.getSource().getPlayerOrException());
        info(c.getSource(), "Blink sent (visible while the Revealing Moon is in your sky)");
        return 1;
    }

    // ---- Thread / Gluttony -----------------------------------------------------------------------------------------

    private static int threadStatus(CommandContext<CommandSource> c, ServerPlayerEntity p) {
        info(c.getSource(), "Thread Moon: " + ThreadFeatures.describe(p));
        return 1;
    }

    private static int gluttonyStatus(CommandContext<CommandSource> c, ServerPlayerEntity p) {
        info(c.getSource(), "Gluttony Moon: " + GluttonyFeatures.describe(p));
        return 1;
    }

    // ---- War ------------------------------------------------------------------------------------------------------

    private static int warStatus(CommandContext<CommandSource> c) {
        MinecraftServer server = c.getSource().getServer();
        if (net.ecmoons.config.Settings.server().warLocked()) info(c.getSource(), "War Moon: LOCKED (war.locked = true) - it cannot happen or be started.");
        info(c.getSource(), "War Moon: " + WarFeatures.describe(server));
        info(c.getSource(), "Arena dimension: " + (WarArena.world(server) != null ? "loaded" : "MISSING (data pack not loaded)")
                + ", Flan Warfare capture active: " + WarFeatures.captureActiveForStatus());
        return 1;
    }

    private static int warStart(CommandContext<CommandSource> c, boolean now, boolean solo) {
        MinecraftServer server = c.getSource().getServer();
        if (WarFeatures.phase(server) != WarState.Phase.IDLE) return fail(c.getSource(), "A tournament is already running: " + WarFeatures.describe(server));
        if (net.ecmoons.config.Settings.server().warLocked()) {
            return fail(c.getSource(), "The War Moon is locked (war.locked = true in ecmoons-common.toml). Set it to false and restart to allow it.");
        }
        if (now) WarFeatures.startNow(server, solo);
        else WarFeatures.startWarning(server, net.ecmoons.config.Settings.server().i("war.warningSeconds"), false);
        ok(c.getSource(), "War Moon tournament started" + (now ? " (no warning)" : "") + (solo ? " in solo test mode (1 player is enough)" : ""));
        return 1;
    }

    private static int warRecords(CommandContext<CommandSource> c) {
        MinecraftServer server = c.getSource().getServer();
        int n = 0;
        for (UUID id : WarRecords.all(server)) {
            CompoundNBT tag = WarRecords.read(server, id);
            info(c.getSource(), (tag != null ? tag.getString("name") + " (" + tag.getString("role") + ", from " + tag.getString("dimension") + ")" : id + " (unreadable)")
                    + (server.getPlayerList().getPlayer(id) != null ? " - online" : " - restored at next login"));
            n++;
        }
        if (n == 0) info(c.getSource(), "No pending War Moon recovery records.");
        return n;
    }

    private static int warRecover(CommandContext<CommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = EntityArgument.getPlayer(c, "player");
        if (WarFeatures.isInvolved(p) && WarFeatures.phase(c.getSource().getServer()) != WarState.Phase.IDLE) {
            return fail(c.getSource(), p.getScoreboardName() + " is in the running tournament; use /ecmoons war cancel to stop it for everyone");
        }
        if (!WarRecords.restore(p)) return fail(c.getSource(), p.getScoreboardName() + " has no pending War Moon record");
        ok(c.getSource(), "Restored " + p.getScoreboardName() + " from their War Moon record");
        return 1;
    }

    private static int warTag(CommandContext<CommandSource> c, String tag, boolean add, String what) throws CommandSyntaxException {
        Collection<ServerPlayerEntity> players = EntityArgument.getPlayers(c, "player");
        for (ServerPlayerEntity p : players) {
            if (add) p.addTag(tag);
            else p.removeTag(tag);
            ok(c.getSource(), p.getScoreboardName() + " " + what + " War Moon battles");
        }
        return players.size();
    }

    private static int warArena(CommandContext<CommandSource> c, boolean build) {
        MinecraftServer server = c.getSource().getServer();
        ServerWorld arena = WarArena.world(server);
        if (arena == null) return fail(c.getSource(), "The arena dimension ecmoons:war_arena is not loaded");
        if (WarFeatures.phase(server) != WarState.Phase.IDLE) return fail(c.getSource(), "Not while a tournament is running");
        if (build) {
            WarArena.Layout l = WarArena.build(arena);
            ok(c.getSource(), "Arena built (" + l.cells.size() + " cells). Look: /execute in ecmoons:war_arena run tp @s 0 "
                    + l.spectator.getY() + " 0 - clear it with /ecmoons war arena clear");
        } else {
            WarArena.clear(arena);
            ok(c.getSource(), "Arena removed");
        }
        return 1;
    }
}
