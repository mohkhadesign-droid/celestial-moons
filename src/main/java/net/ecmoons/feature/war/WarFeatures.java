package net.ecmoons.feature.war;

import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.MoonTeams;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.STitlePacket;
import net.minecraft.particles.RedstoneParticleData;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.Effects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.EnderTeleportEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * War Moon tournament: warning -> enrolment (save + strip + kit) -> preparation in cells -> fight ->
 * optional sudden death -> winner -> everyone restored -> arena removed.
 * <p>
 * Nobody dies in the arena: lethal damage is intercepted and the player is knocked out into the viewing gallery,
 * so there are no drops, graves, XP loss or death counts. Everything is server-side.
 */
public final class WarFeatures {
    public static final String KIT_TAG = "ecmoons_war_kit";
    public static final String EXEMPT_TAG = "ecmoons_war_exempt";
    public static final String SPECTATE_TAG = "ecmoons_war_spectate";
    public static final String CHAMPION_KEY = "ecmoons_champion_ticks";

    private static WarArena.Layout layout;
    private static final Set<UUID> fighters = new LinkedHashSet<>();
    private static final Set<UUID> watchers = new LinkedHashSet<>(); // spectators and knocked-out players
    private static final Map<UUID, BlockPos> cellOf = new HashMap<>();
    private static long ticks, phaseEnd, nextShrink;
    private static int deferrals, safeHalf;
    private static boolean solo, forced;
    private static long suddenDeathEnd;
    private static int lastAnnounced = -1;

    private WarFeatures() {}

    // ---- queries ----------------------------------------------------------------------------------------------

    private static WarState state(MinecraftServer server) {
        return WarState.get(server);
    }

    public static WarState.Phase phase(MinecraftServer server) {
        return state(server).phase;
    }

    public static boolean isInvolved(PlayerEntity p) {
        return fighters.contains(p.getUUID()) || watchers.contains(p.getUUID());
    }

    public static boolean isFighting(PlayerEntity p) {
        MinecraftServer server = p.getServer();
        if (server == null || !fighters.contains(p.getUUID())) return false;
        WarState.Phase ph = phase(server);
        return ph == WarState.Phase.FIGHT || ph == WarState.Phase.SUDDEN_DEATH;
    }

    public static Set<UUID> fighters() {
        return Collections.unmodifiableSet(fighters);
    }

    public static Set<UUID> watchers() {
        return Collections.unmodifiableSet(watchers);
    }

    // ---- messaging -----------------------------------------------------------------------------------------------

    private static void broadcast(MinecraftServer server, IFormattableTextComponent msg) {
        server.getPlayerList().broadcastMessage(msg, net.minecraft.util.text.ChatType.SYSTEM, net.minecraft.util.Util.NIL_UUID);
    }

    private static void title(ServerPlayerEntity p, String title, String sub, TextFormatting colour) {
        p.connection.send(new STitlePacket(5, 40, 10));
        p.connection.send(new STitlePacket(STitlePacket.Type.SUBTITLE, new StringTextComponent(sub).withStyle(TextFormatting.GRAY)));
        p.connection.send(new STitlePacket(STitlePacket.Type.TITLE, new StringTextComponent(title).withStyle(colour, TextFormatting.BOLD)));
    }

    private static String clock(int seconds) {
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------

    /** Called when a world switches to the War Moon. One tournament per War Moon night. */
    public static void onWarMoonRise(ServerWorld world) {
        MinecraftServer server = world.getServer();
        WarState st = state(server);
        long day = server.overworld().getDayTime() / 24000L;
        if (st.phase != WarState.Phase.IDLE || st.lastTournamentDay == day) return;
        st.lastTournamentDay = day;
        st.setDirty();
        startWarning(server, Settings.server().i("war.warningSeconds"), false);
        forced = false;
    }

    public static void startWarning(MinecraftServer server, int seconds, boolean soloMode) {
        WarState st = state(server);
        if (st.phase != WarState.Phase.IDLE) return;
        solo = soloMode;
        forced = true; // cleared again by onWarMoonRise for natural tournaments
        deferrals = 0;
        st.phase = WarState.Phase.WARNING;
        st.setDirty();
        phaseEnd = ticks + seconds * 20L;
        atmo(server, net.ecmoons.net.AtmoPacket.WAR_WARNING, seconds * 20, seconds * 20);
        lastAnnounced = -1;
        if (seconds < 5) return; // forced start without a warning
        broadcast(server, new StringTextComponent("WAR MOON APPROACHES \u2014 Arena battle begins in " + clock(seconds)
                + ". Your equipment will be stored safely. Everyone fights with the same loadout.").withStyle(TextFormatting.DARK_RED, TextFormatting.BOLD));
        for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) title(p, "WAR MOON APPROACHES", "Arena battle in " + clock(seconds), TextFormatting.DARK_RED);
    }

    /** Emergency stop: everyone involved is restored immediately and the arena removed. */
    public static void cancel(MinecraftServer server, String reason) {
        WarState st = state(server);
        if (st.phase == WarState.Phase.IDLE) return;
        broadcast(server, new StringTextComponent("War Moon tournament cancelled: " + reason).withStyle(TextFormatting.RED));
        finish(server);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = net.minecraftforge.fml.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ticks++;
        WarState st = state(server);
        switch (st.phase) {
            case WARNING: tickWarning(server, st); break;
            case PREP: tickPrep(server, st); break;
            case FIGHT:
            case SUDDEN_DEATH: tickFight(server, st); break;
            case ENDING: if (ticks >= phaseEnd) finish(server); break;
            default: break;
        }
        if (st.phase != WarState.Phase.IDLE && ticks % 10 == 0) guard(server);
        if (ticks % 20 == 0) tickChampions(server);
    }

    private static void tickWarning(MinecraftServer server, WarState st) {
        boolean moonUp = false;
        for (ServerWorld w : server.getAllLevels()) if (Moons.is(w, MoonType.WAR)) moonUp = true;
        if (!moonUp && !forced) {
            cancel(server, "the War Moon has set");
            return;
        }
        int left = (int) Math.max(0, (phaseEnd - ticks + 19) / 20);
        if (left != lastAnnounced && (left == 60 || left == 30 || left == 10)) {
            lastAnnounced = left;
            broadcast(server, new StringTextComponent("War Moon arena battle begins in " + clock(left) + ".").withStyle(TextFormatting.RED));
            for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) p.displayClientMessage(
                    new StringTextComponent("War Moon arena in " + clock(left)).withStyle(TextFormatting.RED), true);
        }
        if (ticks < phaseEnd) return;
        if (flanWarfareCaptureActive() && deferrals < Settings.server().i("war.maxDeferrals")) {
            deferrals++;
            int min = Settings.server().i("war.deferMinutes");
            phaseEnd = ticks + min * 1200L;
            atmo(server, net.ecmoons.net.AtmoPacket.WAR_WARNING, min * 1200, min * 1200);
            lastAnnounced = -1;
            broadcast(server, new StringTextComponent("A claim raid is in progress - the War Moon battle is postponed by " + min
                    + " minute(s).").withStyle(TextFormatting.GOLD));
            return;
        }
        if (flanWarfareCaptureActive()) {
            cancel(server, "a claim raid is still in progress");
            return;
        }
        enrol(server, st);
    }

    /** Soft integration with Flan Warfare (no compile dependency): true while any flag capture is running. */
    static boolean flanWarfareCaptureActive() {
        try {
            Class<?> core = Class.forName("net.flanwarfare.core.WarCore");
            Object instance = core.getMethod("get").invoke(null);
            if (instance == null) return false;
            Object capture = core.getMethod("capture").invoke(instance);
            Method active = capture.getClass().getMethod("activeCount");
            return ((Number) active.invoke(capture)).intValue() > 0;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static String captureActiveForStatus() {
        try {
            Class.forName("net.flanwarfare.core.WarCore");
        } catch (ClassNotFoundException e) {
            return "n/a (Flan Warfare not installed or API not found)";
        }
        return String.valueOf(flanWarfareCaptureActive());
    }

    public static boolean eligible(ServerPlayerEntity p) {
        if (!p.isAlive() || p.isSpectator() || p.isCreative()) return false;
        if (p.getTags().contains(EXEMPT_TAG)) return false;
        List<String> excluded = Settings.server().list("war.excludedPlayers");
        return !excluded.contains(p.getScoreboardName()) && !excluded.contains(p.getUUID().toString());
    }

    private static void enrol(MinecraftServer server, WarState st) {
        ServerWorld arena = WarArena.world(server);
        if (arena == null) {
            cancel(server, "the arena dimension ecmoons:war_arena is missing (data pack not loaded)");
            return;
        }
        List<ServerPlayerEntity> candidates = new ArrayList<>();
        List<ServerPlayerEntity> spectators = new ArrayList<>();
        for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) {
            if (p.getTags().contains(SPECTATE_TAG)) spectators.add(p);
            else if (eligible(p)) candidates.add(p);
        }
        Collections.shuffle(candidates);
        int max = Math.min(Settings.server().i("war.maxParticipants"), 16);
        int min = solo ? 1 : Settings.server().i("war.minParticipants");
        if (candidates.size() > max) candidates = new ArrayList<>(candidates.subList(0, max));
        if (candidates.size() < min) {
            cancel(server, "not enough players (" + candidates.size() + "/" + min + ")");
            return;
        }
        layout = WarArena.build(arena);
        atmo(server, net.ecmoons.net.AtmoPacket.WAR_SUMMON, 0, 0);
        fighters.clear();
        watchers.clear();
        cellOf.clear();
        int cell = 0;
        List<ServerPlayerEntity> order = new ArrayList<>(candidates);
        for (ServerPlayerEntity p : order) {
            if (cell >= layout.cells.size()) break;
            String problem = WarRecords.save(p, "fighter");
            if (problem != null) {
                p.sendMessage(new StringTextComponent("You were not entered into the War Moon battle: " + problem).withStyle(TextFormatting.RED), net.minecraft.util.Util.NIL_UUID);
                EcMoons.LOGGER.warn("War Moon: {} not enrolled: {}", p.getScoreboardName(), problem);
                continue;
            }
            st.involved.add(p.getUUID());
            st.setDirty();
            WarRecords.strip(p);
            giveKit(p);
            BlockPos c = layout.cells.get(cell++);
            cellOf.put(p.getUUID(), c);
            fighters.add(p.getUUID());
            move(p, arena, c);
        }
        for (ServerPlayerEntity p : spectators) addWatcher(server, st, p);
        if (fighters.size() < min) {
            cancel(server, "not enough players could be entered safely");
            return;
        }
        st.phase = WarState.Phase.PREP;
        st.setDirty();
        phaseEnd = ticks + Settings.server().i("war.prepSeconds") * 20L;
        lastAnnounced = -1;
        broadcast(server, new StringTextComponent(fighters.size() + " players entered the War Moon arena.").withStyle(TextFormatting.RED));
    }

    private static void addWatcher(MinecraftServer server, WarState st, ServerPlayerEntity p) {
        String problem = WarRecords.save(p, "spectator");
        if (problem != null) {
            p.sendMessage(new StringTextComponent("You cannot spectate the War Moon battle: " + problem).withStyle(TextFormatting.RED), net.minecraft.util.Util.NIL_UUID);
            return;
        }
        st.involved.add(p.getUUID());
        st.setDirty();
        WarRecords.strip(p);
        watchers.add(p.getUUID());
        move(p, WarArena.world(server), layout.spectator);
    }

    private static void move(ServerPlayerEntity p, ServerWorld world, BlockPos pos) {
        WarState.allowTeleport = true;
        try {
            // face the arena centre (cells look through their gate)
            float yaw = (pos.getX() == 0 && pos.getZ() == 0) ? p.yRot
                    : (float) Math.toDegrees(Math.atan2(pos.getX() + 0.5, -(pos.getZ() + 0.5)));
            p.teleportTo(world, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0f);
        } finally {
            WarState.allowTeleport = false;
        }
    }

    public static void giveKit(ServerPlayerEntity p) {
        for (String entry : Settings.server().list("war.kit")) {
            String slot = null, item = entry.trim();
            int eq = item.indexOf('=');
            if (eq > 0) {
                slot = item.substring(0, eq).trim();
                item = item.substring(eq + 1).trim();
            }
            int count = 1;
            int star = item.indexOf('*');
            if (star > 0) {
                try {
                    count = Integer.parseInt(item.substring(star + 1).trim());
                } catch (NumberFormatException ignored) {
                }
                item = item.substring(0, star).trim();
            }
            Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation(item));
            if (it == null || it == net.minecraft.item.Items.AIR) continue;
            ItemStack stack = new ItemStack(it, count);
            stack.getOrCreateTag().putBoolean(KIT_TAG, true);
            EquipmentSlotType eqSlot = slot == null ? null : slotOf(slot);
            if (eqSlot != null) p.setItemSlot(eqSlot, stack);
            else p.inventory.add(stack);
        }
        p.inventoryMenu.broadcastChanges();
    }

    @Nullable
    private static EquipmentSlotType slotOf(String s) {
        switch (s) {
            case "head": return EquipmentSlotType.HEAD;
            case "chest": return EquipmentSlotType.CHEST;
            case "legs": return EquipmentSlotType.LEGS;
            case "feet": return EquipmentSlotType.FEET;
            case "offhand": return EquipmentSlotType.OFFHAND;
            default: return null;
        }
    }

    private static void tickPrep(MinecraftServer server, WarState st) {
        int left = (int) Math.max(0, (phaseEnd - ticks + 19) / 20);
        if (left != lastAnnounced) {
            lastAnnounced = left;
            for (UUID id : fighters) {
                ServerPlayerEntity p = server.getPlayerList().getPlayer(id);
                if (p != null && left > 0) title(p, String.valueOf(left), "Gates open soon - no attacking yet", TextFormatting.GOLD);
            }
        }
        if (ticks < phaseEnd) return;
        WarArena.openGates(WarArena.world(server), layout);
        st.phase = WarState.Phase.FIGHT;
        atmo(server, net.ecmoons.net.AtmoPacket.WAR_FIGHT, 0, 0);
        st.setDirty();
        phaseEnd = ticks + Settings.server().i("war.matchSeconds") * 20L;
        safeHalf = layout.half;
        for (UUID id : fighters) {
            ServerPlayerEntity p = server.getPlayerList().getPlayer(id);
            if (p != null) title(p, "FIGHT!", "Last player standing wins", TextFormatting.RED);
        }
        broadcast(server, new StringTextComponent("The War Moon battle has begun!").withStyle(TextFormatting.DARK_RED, TextFormatting.BOLD));
    }

    private static void tickFight(MinecraftServer server, WarState st) {
        if (!solo && fighters.size() <= 1) {
            declare(server, st, fighters.isEmpty() ? null : fighters.iterator().next());
            return;
        }
        if (fighters.isEmpty()) {
            declare(server, st, null);
            return;
        }
        if (st.phase == WarState.Phase.FIGHT && ticks >= phaseEnd) {
            st.phase = WarState.Phase.SUDDEN_DEATH;
            st.setDirty();
            nextShrink = ticks;
            suddenDeathEnd = ticks + 180 * 20L;
            broadcast(server, new StringTextComponent("SUDDEN DEATH - the arena is closing in. Stay inside the red line!")
                    .withStyle(TextFormatting.DARK_RED, TextFormatting.BOLD));
        }
        if (st.phase == WarState.Phase.SUDDEN_DEATH && ticks >= suddenDeathEnd) {
            broadcast(server, new StringTextComponent("Sudden death ran out of time.").withStyle(TextFormatting.GRAY));
            declare(server, st, null);
            return;
        }
        if (st.phase == WarState.Phase.SUDDEN_DEATH) {
            ServerWorld arena = WarArena.world(server);
            if (ticks >= nextShrink) {
                safeHalf = Math.max(2, safeHalf - 2);
                nextShrink = ticks + Settings.server().i("war.suddenDeathShrinkSeconds") * 20L;
            }
            if (ticks % 20 == 0) {
                RedstoneParticleData red = new RedstoneParticleData(0.9f, 0.1f, 0.1f, 1.5f);
                for (int k = -safeHalf; k <= safeHalf; k += 2) {
                    for (int[] c : new int[][]{{k, -safeHalf}, {k, safeHalf}, {-safeHalf, k}, {safeHalf, k}}) {
                        arena.sendParticles(red, c[0] + 0.5, WarArena.FLOOR_Y + 1.5, c[1] + 0.5, 2, 0, 0.6, 0, 0);
                    }
                }
                for (UUID id : new ArrayList<>(fighters)) {
                    ServerPlayerEntity p = server.getPlayerList().getPlayer(id);
                    if (p != null && (Math.abs(p.getX()) > safeHalf + 0.5 || Math.abs(p.getZ()) > safeHalf + 0.5)) {
                        p.hurt(DamageSource.OUT_OF_WORLD, 2f);
                    }
                }
            }
        }
    }

    private static void declare(MinecraftServer server, WarState st, @Nullable UUID winner) {
        st.phase = WarState.Phase.ENDING;
        st.setDirty();
        phaseEnd = ticks + Settings.server().i("war.celebrationSeconds") * 20L;
        if (winner == null) {
            broadcast(server, new StringTextComponent("The War Moon battle ended with no winner.").withStyle(TextFormatting.GRAY));
            return;
        }
        ServerPlayerEntity p = server.getPlayerList().getPlayer(winner);
        String name = p != null ? p.getScoreboardName() : winner.toString();
        MoonTeams teams = MoonTeams.get(server);
        String team = teams.displayName(teams.teamOf(winner));
        broadcast(server, new StringTextComponent("WAR CHAMPION: " + name + (team != null ? " of " + team : "") + "!")
                .withStyle(TextFormatting.GOLD, TextFormatting.BOLD));
        for (ServerPlayerEntity o : server.getPlayerList().getPlayers()) title(o, "WAR CHAMPION", name + (team != null ? " - " + team : ""), TextFormatting.GOLD);
        if (p != null) {
            setChampionTicks(p, Settings.server().i("war.championMinutes") * 60 * 20); // refresh, never stack
        }
    }

    /** Restore everyone online, keep records for offline players, then remove the arena. */
    /** Visual only: tell every client where the tournament is, for the War Moon atmosphere build-up. */
    private static void atmo(MinecraftServer server, int phase, int ticksLeft, int total) {
        net.ecmoons.net.Network.CHANNEL.send(net.minecraftforge.fml.network.PacketDistributor.ALL.noArg(),
                new net.ecmoons.net.AtmoPacket(net.ecmoons.net.AtmoPacket.WAR, phase, ticksLeft, total));
    }

    private static void finish(MinecraftServer server) {
        atmo(server, net.ecmoons.net.AtmoPacket.WAR_IDLE, 0, 0);
        WarState st = state(server);
        Set<UUID> pending = new LinkedHashSet<>(st.involved);
        for (UUID id : pending) {
            ServerPlayerEntity p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                WarRecords.restore(p);
                st.involved.remove(id);
            }
        }
        fighters.clear();
        watchers.clear();
        cellOf.clear();
        st.phase = WarState.Phase.IDLE;
        st.setDirty();
        ServerWorld arena = WarArena.world(server);
        if (arena != null) {
            boolean empty = arena.players().isEmpty();
            if (empty) WarArena.clear(arena);
            else EcMoons.LOGGER.warn("War Moon: arena not cleared because players are still inside");
        }
        layout = null;
        solo = false;
        forced = false;
    }

    // ---- knockouts and rules --------------------------------------------------------------------------------------

    /** Lethal damage to a fighter becomes a knockout; watchers never take damage. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent event) {
        if (!(event.getEntityLiving() instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity p = (ServerPlayerEntity) event.getEntityLiving();
        if (watchers.contains(p.getUUID())) {
            event.setCanceled(true);
            return;
        }
        if (fighters.contains(p.getUUID()) && event.getAmount() >= p.getHealth() + p.getAbsorptionAmount()) {
            event.setCanceled(true);
            knockOut(p, event.getSource().getEntity());
        }
    }

    /** Fallback (e.g. /kill): involved players never actually die in the arena. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntityLiving() instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity p = (ServerPlayerEntity) event.getEntityLiving();
        if (!isInvolved(p)) return;
        event.setCanceled(true);
        p.setHealth(p.getMaxHealth());
        if (fighters.contains(p.getUUID())) knockOut(p, event.getSource().getEntity());
    }

    private static void knockOut(ServerPlayerEntity p, @Nullable Entity by) {
        MinecraftServer server = p.getServer();
        fighters.remove(p.getUUID());
        watchers.add(p.getUUID());
        p.inventory.clearContent();
        p.removeAllEffects();
        p.clearFire();
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.inventoryMenu.broadcastChanges();
        if (layout != null) move(p, WarArena.world(server), layout.spectator);
        String msg = p.getScoreboardName() + " was knocked out" + (by instanceof PlayerEntity ? " by " + ((PlayerEntity) by).getScoreboardName() : "")
                + " (" + fighters.size() + " left)";
        broadcast(server, new StringTextComponent(msg).withStyle(TextFormatting.RED));
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttack(LivingAttackEvent event) {
        Entity attacker = event.getSource().getEntity();
        LivingEntity victim = event.getEntityLiving();
        boolean victimInvolved = victim instanceof PlayerEntity && isInvolved((PlayerEntity) victim);
        boolean attackerInvolved = attacker instanceof PlayerEntity && isInvolved((PlayerEntity) attacker);
        if (!victimInvolved && !attackerInvolved) return;
        if (victim instanceof PlayerEntity && watchers.contains(victim.getUUID())) {
            event.setCanceled(true);
            return;
        }
        if (attacker instanceof PlayerEntity && attacker != victim) {
            if (!(isFighting((PlayerEntity) attacker) && victim instanceof PlayerEntity && isFighting((PlayerEntity) victim))) event.setCanceled(true);
        }
    }

    /** Called from ServerPlayerEntityMixin: PvP between two fighters during the match, regardless of server PvP/teams. */
    @Nullable
    public static Boolean pvpOverride(ServerPlayerEntity self, PlayerEntity other) {
        if (!isInvolved(self) && !isInvolved(other)) return null;
        return isFighting(self) && isFighting(other);
    }

    private static boolean inArena(@Nullable World w) {
        return w != null && w.dimension().equals(WarArena.DIMENSION);
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getWorld() instanceof World && inArena((World) event.getWorld())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getWorld() instanceof World && inArena((World) event.getWorld())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Start event) {
        if (inArena(event.getWorld())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onBucket(FillBucketEvent event) {
        if (inArena(event.getWorld())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onUseBlock(PlayerInteractEvent.RightClickBlock event) {
        if (inArena(event.getWorld()) && !event.getPlayer().hasPermissions(2)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onToss(ItemTossEvent event) {
        PlayerEntity p = event.getPlayer();
        if (!isInvolved(p) && !inArena(p.level)) return;
        ItemStack stack = event.getEntityItem().getItem().copy();
        event.setCanceled(true);
        if (!p.inventory.add(stack)) p.inventory.setItem(p.inventory.selected, stack);
        p.inventoryMenu.broadcastChanges();
    }

    @SubscribeEvent
    public static void onPearl(EnderTeleportEvent event) {
        if (event.getEntityLiving() instanceof PlayerEntity && isInvolved((PlayerEntity) event.getEntityLiving())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onTravel(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof PlayerEntity && isInvolved((PlayerEntity) event.getEntity()) && !WarState.allowTeleport) {
            event.setCanceled(true);
        }
    }

    /** No mobs, pets, summons or dropped items in the arena; projectiles are allowed. */
    @SubscribeEvent
    public static void onJoin(EntityJoinWorldEvent event) {
        if (!inArena(event.getWorld()) || event.getWorld().isClientSide) return;
        Entity e = event.getEntity();
        if (e instanceof PlayerEntity || e instanceof ProjectileEntity) return;
        if (e instanceof LivingEntity || e instanceof ItemEntity) event.setCanceled(true);
    }

    /** Keep involved players where they belong (catches /tp, modded teleports and glitches). */
    private static void guard(MinecraftServer server) {
        ServerWorld arena = WarArena.world(server);
        if (arena == null || layout == null) return;
        for (UUID id : fighters) {
            ServerPlayerEntity p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            if (!inArena(p.level) || !WarArena.inside(layout, p.blockPosition()) || p.getY() > layout.spectator.getY() - 2) {
                BlockPos back = phase(server) == WarState.Phase.PREP ? cellOf.getOrDefault(id, layout.center()) : layout.center();
                move(p, arena, back);
            }
            p.abilities.flying = false;
        }
        for (UUID id : watchers) {
            ServerPlayerEntity p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            if (!inArena(p.level) || p.getY() < layout.spectator.getY() - 1 || !WarArena.inside(layout, p.blockPosition())) {
                move(p, arena, layout.spectator);
            }
            p.getFoodData().setFoodLevel(20);
        }
    }

    // ---- disconnects, logins, restarts -----------------------------------------------------------------------------

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PlayerEntity p = event.getPlayer();
        if (fighters.remove(p.getUUID())) {
            MinecraftServer server = p.getServer();
            if (server != null) broadcast(server, new StringTextComponent(p.getScoreboardName() + " left the arena (forfeit, "
                    + fighters.size() + " left)").withStyle(TextFormatting.GRAY));
        }
        watchers.remove(p.getUUID());
    }

    /** A pending recovery record is applied at login (after a disconnect, crash or restart). */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayerEntity)) return;
        ServerPlayerEntity p = (ServerPlayerEntity) event.getPlayer();
        MinecraftServer server = p.getServer();
        if (server == null || !WarRecords.exists(server, p.getUUID())) return;
        if (WarRecords.restore(p)) {
            WarState st = state(server);
            st.involved.remove(p.getUUID());
            st.setDirty();
            p.sendMessage(new StringTextComponent("Your equipment and position from before the War Moon battle were restored.")
                    .withStyle(TextFormatting.GREEN), net.minecraft.util.Util.NIL_UUID);
        }
    }

    /** A session interrupted by a crash or restart cannot continue: finish it safely. */
    public static void onServerStarted(FMLServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        WarState st = state(server);
        if (st.phase != WarState.Phase.IDLE) {
            EcMoons.LOGGER.warn("War Moon: an interrupted tournament ({}) was found - players are restored when they log in", st.phase);
            st.phase = WarState.Phase.IDLE;
            st.setDirty();
        }
        ServerWorld arena = WarArena.world(server);
        if (arena != null && arena.players().isEmpty()) WarArena.clear(arena);
        fighters.clear();
        watchers.clear();
        layout = null;
    }

    // ---- champion reward --------------------------------------------------------------------------------------------

    /** Champion time is kept in the part of the player data that survives death (PlayerPersisted). */
    public static int championTicks(PlayerEntity p) {
        return p.getPersistentData().getCompound(PlayerEntity.PERSISTED_NBT_TAG).getInt(CHAMPION_KEY);
    }

    public static void setChampionTicks(PlayerEntity p, int ticksLeft) {
        net.minecraft.nbt.CompoundNBT data = p.getPersistentData();
        net.minecraft.nbt.CompoundNBT persisted = data.getCompound(PlayerEntity.PERSISTED_NBT_TAG);
        if (ticksLeft > 0) persisted.putInt(CHAMPION_KEY, ticksLeft);
        else persisted.remove(CHAMPION_KEY);
        data.put(PlayerEntity.PERSISTED_NBT_TAG, persisted);
    }

    private static final RedstoneParticleData GOLD = new RedstoneParticleData(1.0f, 0.78f, 0.2f, 1.0f);
    private static final RedstoneParticleData CRIMSON = new RedstoneParticleData(0.75f, 0.08f, 0.12f, 1.0f);

    /** Once a second: count down online time, keep Haste I topped up and show the gold/crimson aura. */
    private static void tickChampions(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) {
            int left = championTicks(p);
            if (left <= 0) continue;
            if (isInvolved(p)) continue; // suppressed during matches (and the timer pauses)
            left -= 20;
            setChampionTicks(p, left);
            if (left <= 0) {
                p.displayClientMessage(new StringTextComponent("Your War Champion reward has ended.").withStyle(TextFormatting.GRAY), true);
                continue;
            }
            EffectInstance current = p.getEffect(Effects.DIG_SPEED);
            if (current == null || (current.getAmplifier() == 0 && current.getDuration() < 220)) {
                p.addEffect(new EffectInstance(Effects.DIG_SPEED, 300, 0, true, false, true));
            }
            if (!p.isSpectator() && !p.isInvisible()) {
                ServerWorld w = p.getLevel();
                double a = (server.getTickCount() / 20 % 8) * Math.PI / 4;
                for (int k = 0; k < 4; k++) {
                    double ang = a + k * Math.PI / 2;
                    w.sendParticles(k % 2 == 0 ? GOLD : CRIMSON, p.getX() + Math.cos(ang) * 0.6, p.getY() + 0.3 + k * 0.4,
                            p.getZ() + Math.sin(ang) * 0.6, 1, 0.05, 0.05, 0.05, 0);
                }
            }
        }
    }

    public static boolean isChampion(PlayerEntity p) {
        return championTicks(p) > 0;
    }

    public static void clearChampion(PlayerEntity p) {
        setChampionTicks(p, 0);
    }

    public static String describe(MinecraftServer server) {
        WarState st = state(server);
        return "phase " + st.phase + ", fighters " + fighters.size() + ", watchers " + watchers.size()
                + ", pending records " + WarRecords.all(server).size() + (solo ? ", solo test" : "")
                + (st.phase != WarState.Phase.IDLE ? ", " + Math.max(0, (phaseEnd - ticks) / 20) + "s left in phase" : "");
    }

    /** Force-start (skips the warning). */
    public static void startNow(MinecraftServer server, boolean soloMode) {
        WarState st = state(server);
        if (st.phase == WarState.Phase.IDLE) startWarning(server, 1, soloMode);
        phaseEnd = ticks;
    }
}
