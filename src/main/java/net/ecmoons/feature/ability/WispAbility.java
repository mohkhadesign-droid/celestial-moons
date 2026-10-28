package net.ecmoons.feature.ability;

import net.ecmoons.MoonType;
import net.ecmoons.config.Settings;
import net.ecmoons.feature.ability.wisp.ModEntities;
import net.ecmoons.feature.ability.wisp.ObserverEntity;
import net.ecmoons.feature.ability.wisp.WispEntity;
import net.ecmoons.net.Network;
import net.ecmoons.net.WispMenuPacket;
import net.ecmoons.net.WispStatePacket;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Direction;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wisp Moon: Soul Observers. The Moon Ability key opens a small menu (Pilot Wisp / View Observers / Dismiss).
 * <ul>
 *   <li><b>Piloting</b> spawns a small visible wisp entity at the player's eyes and switches the player's camera to it.
 *   The body stays where it is, visible and vulnerable; it can't walk, attack, use items or interact. The server moves
 *   the wisp from the pilot's movement keys with normal block collision (walls and closed doors stop it), keeps it
 *   within range of the body, and never moves it into a chunk that isn't loaded and ticking or that is outside the
 *   server's view distance from the body. The pilot's client predicts the same movement for a smooth camera.</li>
 *   <li>While piloting, an explicit input (the Use key, with an on-screen hint) places a stationary soul observer on
 *   the block face the wisp looks at, within reach, where the owner could place a block. Up to maxObservers.</li>
 *   <li><b>Viewing</b> switches the camera to one observer (numbered list, out-of-range ones are marked). The view
 *   can only turn a limited amount from the direction the observer faces. No markers, no x-ray: it is just a camera.</li>
 * </ul>
 * Piloting and viewing are ability states, never a game-mode change. Any damage to the body, moving the body, death,
 * logout, a dimension change, the moon ending, or losing the wisp/observer returns the player to their body.
 * Observers disappear when the moon ends, when the owner leaves (logout, death, dimension change), and are never saved.
 */
public final class WispAbility {
    static final class Pilot {
        WispEntity wisp;
        Vector3d anchor;
        long start;
        float forward, strafe, up, yaw, pitch;
        boolean warnedEdge;
    }

    static final class View {
        ObserverEntity observer;
        Vector3d anchor;
    }

    public static final byte PILOT = 0, VIEW = 1, DISMISS = 2, DISMISS_ALL = 3, PLACE = 4, EXIT = 5, MENU = 6;

    private static final Map<UUID, Pilot> PILOTS = new HashMap<>();
    private static final Map<UUID, View> VIEWS = new HashMap<>();
    private static final Map<UUID, List<ObserverEntity>> OBSERVERS = new HashMap<>();

    private WispAbility() {}

    private static Settings s() {
        return Settings.server();
    }

    private static void info(ServerPlayerEntity p, String msg) {
        p.displayClientMessage(new StringTextComponent(msg).withStyle(TextFormatting.AQUA), true);
    }

    // ---- queries ----------------------------------------------------------------------------------------------------

    public static boolean isControlled(WispEntity e) {
        for (Pilot p : PILOTS.values()) if (p.wisp == e) return true;
        return false;
    }

    public static boolean isRegistered(ObserverEntity e) {
        List<ObserverEntity> l = e.owner == null ? null : OBSERVERS.get(e.owner);
        return l != null && l.contains(e);
    }

    /** Piloting or viewing (the body is idle and the camera is elsewhere). */
    public static boolean inControl(PlayerEntity p) {
        return PILOTS.containsKey(p.getUUID()) || VIEWS.containsKey(p.getUUID());
    }

    public static boolean piloting(PlayerEntity p) {
        return PILOTS.containsKey(p.getUUID());
    }

    public static boolean viewing(PlayerEntity p) {
        return VIEWS.containsKey(p.getUUID());
    }

    @Nullable
    public static WispEntity wispOf(PlayerEntity p) {
        Pilot pi = PILOTS.get(p.getUUID());
        return pi == null ? null : pi.wisp;
    }

    @Nullable
    public static ObserverEntity viewedBy(PlayerEntity p) {
        View v = VIEWS.get(p.getUUID());
        return v == null ? null : v.observer;
    }

    public static List<ObserverEntity> observers(PlayerEntity p) {
        List<ObserverEntity> l = OBSERVERS.get(p.getUUID());
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    public static int pilotCount() {
        return PILOTS.size();
    }

    public static int observerCount() {
        int n = 0;
        for (List<ObserverEntity> l : OBSERVERS.values()) n += l.size();
        return n;
    }

    // ---- input ------------------------------------------------------------------------------------------------------

    static void onPress(ServerPlayerEntity p) {
        String why = MoonAbilities.blocked(p, MoonType.WISP);
        if (why != null) {
            MoonAbilities.fail(p, why);
            return;
        }
        sendMenu(p);
    }

    public static void onAction(ServerPlayerEntity p, byte action, int index) {
        if (action == EXIT) {
            end(p, null, true);
            return;
        }
        if (action == PLACE) {
            place(p);
            return;
        }
        if (inControl(p)) return; // the menu is only used from the body
        String why = MoonAbilities.blocked(p, MoonType.WISP);
        if (why != null) {
            MoonAbilities.fail(p, why);
            return;
        }
        switch (action) {
            case PILOT: startPilot(p); break;
            case VIEW: startView(p, index); break;
            case DISMISS: dismiss(p, index); sendMenu(p); break;
            case DISMISS_ALL: removeObservers(p.getUUID(), null); sendMenu(p); break;
            case MENU: sendMenu(p); break;
            default:
        }
    }

    public static void onInput(ServerPlayerEntity p, float forward, float strafe, float up, float yaw, float pitch) {
        Pilot pi = PILOTS.get(p.getUUID());
        if (pi == null) return;
        if (!Float.isFinite(forward) || !Float.isFinite(strafe) || !Float.isFinite(up) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        pi.forward = MathHelper.clamp(forward, -1, 1);
        pi.strafe = MathHelper.clamp(strafe, -1, 1);
        pi.up = MathHelper.clamp(up, -1, 1);
        pi.yaw = MathHelper.wrapDegrees(yaw);
        pi.pitch = MathHelper.clamp(pitch, -90, 90);
    }

    static void sendMenu(ServerPlayerEntity p) {
        List<ObserverEntity> list = observers(p);
        int n = list.size();
        int[] numbers = new int[n], ids = new int[n];
        float[] dist = new float[n];
        boolean[] inRange = new boolean[n];
        Vector3d body = bodyPos(p);
        for (int i = 0; i < n; i++) {
            ObserverEntity o = list.get(i);
            numbers[i] = o.number;
            ids[i] = o.getId();
            dist[i] = (float) Math.sqrt(o.distanceToSqr(body));
            inRange[i] = canView(p, o);
        }
        Network.send(p, new WispMenuPacket(MoonAbilities.cooldownLeft(p, MoonType.WISP), s().i("wisp.maxObservers"), numbers, ids, dist, inRange));
    }

    private static Vector3d feet(PlayerEntity p) {
        return new Vector3d(p.getX(), p.getY(), p.getZ());
    }

    /** Tests on a dedicated server use fake players, which are not in the player list. */
    public static final Map<UUID, ServerPlayerEntity> TEST_PLAYERS = new HashMap<>();

    @Nullable
    private static ServerPlayerEntity player(MinecraftServer server, UUID id) {
        ServerPlayerEntity p = server.getPlayerList().getPlayer(id);
        return p != null ? p : TEST_PLAYERS.get(id);
    }

    public static boolean loadedForTest(ServerPlayerEntity p, Vector3d at) {
        return loadedNear(p, at);
    }

    private static Vector3d bodyPos(ServerPlayerEntity p) {
        return new Vector3d(p.getX(), p.getEyeY(), p.getZ());
    }

    private static boolean canView(ServerPlayerEntity p, ObserverEntity o) {
        double r = s().d("wisp.viewRange");
        return o.isAlive() && o.level == p.level && o.distanceToSqr(bodyPos(p)) <= r * r && loadedNear(p, o.position());
    }

    /** Loaded, entity-ticking and within the server's view distance of the body: nothing is ever force-loaded. */
    private static boolean loadedNear(ServerPlayerEntity p, Vector3d at) {
        ServerWorld w = p.getLevel();
        BlockPos pos = new BlockPos(at);
        if (!w.hasChunkAt(pos) || !w.getChunkSource().isEntityTickingChunk(new net.minecraft.util.math.ChunkPos(pos))) return false;
        int view = Math.max(2, w.getServer().getPlayerList().getViewDistance()) - 1;
        int cx = MathHelper.floor(at.x) >> 4, cz = MathHelper.floor(at.z) >> 4;
        int px = MathHelper.floor(p.getX()) >> 4, pz = MathHelper.floor(p.getZ()) >> 4;
        return Math.abs(cx - px) <= view && Math.abs(cz - pz) <= view;
    }

    // ---- piloting ---------------------------------------------------------------------------------------------------

    private static void startPilot(ServerPlayerEntity p) {
        int cd = MoonAbilities.cooldownLeft(p, MoonType.WISP);
        if (cd > 0) {
            MoonAbilities.fail(p, "Wisp ready in " + (cd + 19) / 20 + "s");
            return;
        }
        if (p.isPassenger() || p.isSleeping() || p.isFallFlying()) {
            MoonAbilities.fail(p, "You can't call a wisp now");
            return;
        }
        WispEntity wisp = ModEntities.WISP.get().create(p.level);
        if (wisp == null) return;
        Vector3d eye = bodyPos(p);
        double half = wisp.getBbHeight() / 2;
        wisp.moveTo(eye.x, eye.y - half, eye.z, p.yRot, p.xRot);
        if (!p.level.noCollision(wisp, wisp.getBoundingBox())) {
            MoonAbilities.fail(p, "Not enough room");
            return;
        }
        p.level.addFreshEntity(wisp);
        Pilot pi = new Pilot();
        pi.wisp = wisp;
        pi.anchor = feet(p);
        pi.start = MoonAbilities.now();
        pi.yaw = p.yRot;
        pi.pitch = p.xRot;
        PILOTS.put(p.getUUID(), pi);
        p.level.playSound(null, eye.x, eye.y, eye.z, SoundEvents.SOUL_ESCAPE, SoundCategory.PLAYERS, 0.8F, 1.2F);
        sendState(p);
    }

    /** End piloting or viewing and return the camera to the body. */
    public static void end(ServerPlayerEntity p, @Nullable String why, boolean cooldown) {
        Pilot pi = PILOTS.remove(p.getUUID());
        View v = VIEWS.remove(p.getUUID());
        if (pi != null) {
            if (pi.wisp != null && !pi.wisp.removed) {
                if (pi.wisp.level instanceof ServerWorld) ((ServerWorld) pi.wisp.level).sendParticles(ParticleTypes.SOUL,
                        pi.wisp.getX(), pi.wisp.getY() + 0.25, pi.wisp.getZ(), 4, 0.1, 0.1, 0.1, 0.01);
                pi.wisp.remove();
            }
            if (cooldown) MoonAbilities.startCooldown(p, MoonType.WISP, s().i("wisp.pilotCooldownSeconds"));
        }
        if (pi != null || v != null) {
            sendState(p);
            if (why != null) MoonAbilities.fail(p, why);
        }
    }

    static void tickEnd() {
        if (PILOTS.isEmpty() && VIEWS.isEmpty() && OBSERVERS.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        long now = MoonAbilities.now();
        double tol = s().d("wisp.bodyMoveTolerance");
        for (Map.Entry<UUID, Pilot> e : new ArrayList<>(PILOTS.entrySet())) {
            ServerPlayerEntity p = player(server, e.getKey());
            Pilot pi = e.getValue();
            if (p == null) {
                PILOTS.remove(e.getKey());
                if (pi.wisp != null) pi.wisp.remove();
                continue;
            }
            String why = !p.isAlive() ? "" : MoonAbilities.blocked(p, MoonType.WISP) != null ? "The wisp fades"
                    : pi.wisp == null || pi.wisp.removed || pi.wisp.level != p.level ? "Your wisp was destroyed"
                    : feet(p).distanceTo(pi.anchor) > tol ? "Your body was moved"
                    : now - pi.start > s().i("wisp.maxPilotSeconds") * 20L ? "The wisp fades" : null;
            if (why != null) {
                end(p, why.isEmpty() ? null : why, true);
                continue;
            }
            movePilot(p, pi);
        }
        for (Map.Entry<UUID, View> e : new ArrayList<>(VIEWS.entrySet())) {
            ServerPlayerEntity p = player(server, e.getKey());
            View v = e.getValue();
            if (p == null) {
                VIEWS.remove(e.getKey());
                continue;
            }
            String why = !p.isAlive() ? "" : MoonAbilities.blocked(p, MoonType.WISP) != null ? "The feed fades"
                    : !v.observer.isAlive() ? "The observer was destroyed"
                    : !canView(p, v.observer) ? "Observer out of range"
                    : feet(p).distanceTo(v.anchor) > tol ? "Your body was moved" : null;
            if (why != null) end(p, why.isEmpty() ? null : why, false);
        }
        // observers: drop removed ones; all of them go when the moon is no longer up where they are
        for (Iterator<Map.Entry<UUID, List<ObserverEntity>>> it = OBSERVERS.entrySet().iterator(); it.hasNext(); ) {
            List<ObserverEntity> l = it.next().getValue();
            for (Iterator<ObserverEntity> oi = l.iterator(); oi.hasNext(); ) {
                ObserverEntity o = oi.next();
                if (!o.isAlive()) oi.remove();
                else if (!net.ecmoons.Moons.is(o.level, MoonType.WISP)) {
                    o.remove();
                    oi.remove();
                }
            }
            if (l.isEmpty()) it.remove();
        }
    }

    private static void movePilot(ServerPlayerEntity p, Pilot pi) {
        WispEntity w = pi.wisp;
        Vector3d delta = WispEntity.motion(pi.forward, pi.strafe, pi.up, pi.yaw, s().d("wisp.pilotSpeed"));
        Vector3d body = bodyPos(p);
        Vector3d center = w.position().add(0, w.getBbHeight() / 2, 0);
        delta = WispEntity.clampToRange(center, delta, body, s().d("wisp.pilotRange"));
        if (delta.lengthSqr() > 1e-8) {
            Vector3d target = center.add(delta);
            // never into unloaded or non-ticking chunks, or beyond what the server sends to this player
            if (!loadedNear(p, target) || !loadedNear(p, target.add(Math.signum(delta.x) * 0.5, 0, Math.signum(delta.z) * 0.5))) {
                if (!pi.warnedEdge) info(p, "The wisp can't go further that way");
                pi.warnedEdge = true;
            } else {
                pi.warnedEdge = false;
                w.move(MoverType.SELF, delta);
            }
        }
        w.yRot = pi.yaw;
        w.xRot = pi.pitch;
        w.setDeltaMovement(Vector3d.ZERO);
    }

    // ---- observers --------------------------------------------------------------------------------------------------

    private static void place(ServerPlayerEntity p) {
        Pilot pi = PILOTS.get(p.getUUID());
        if (pi == null || pi.wisp == null || pi.wisp.removed) {
            MoonAbilities.fail(p, "Pilot your wisp to place observers");
            return;
        }
        List<ObserverEntity> mine = OBSERVERS.computeIfAbsent(p.getUUID(), k -> new ArrayList<>());
        int max = s().i("wisp.maxObservers");
        if (mine.size() >= max) {
            MoonAbilities.fail(p, "Observer limit reached (" + mine.size() + "/" + max + "): dismiss one first");
            return;
        }
        WispEntity w = pi.wisp;
        Vector3d from = w.position().add(0, w.getBbHeight() / 2, 0);
        Vector3d look = Vector3d.directionFromRotation(pi.pitch, pi.yaw);
        Vector3d to = from.add(look.scale(s().d("wisp.placeReach")));
        BlockRayTraceResult hit = w.level.clip(new RayTraceContext(from, to, RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, w));
        if (hit.getType() != RayTraceResult.Type.BLOCK) {
            MoonAbilities.fail(p, "Look at a nearby block face");
            return;
        }
        Direction face = hit.getDirection();
        ObserverEntity o = ModEntities.OBSERVER.get().create(p.level);
        if (o == null) return;
        double half = o.getBbWidth() / 2 + 0.02;
        Vector3d c = hit.getLocation().add(face.getStepX() * half, face.getStepY() * half, face.getStepZ() * half);
        float yaw, pitch;
        if (face.getAxis().isHorizontal()) {
            yaw = face.toYRot();
            pitch = 0;
        } else {
            yaw = MathHelper.wrapDegrees(pi.yaw + 180); // on a floor or ceiling: face back the way the wisp came
            pitch = face == Direction.UP ? -10 : 30;
        }
        o.moveTo(c.x, c.y - o.getBbHeight() / 2, c.z, yaw, pitch);
        AxisAlignedBB box = o.getBoundingBox();
        if (!p.level.noCollision(o, box)) {
            MoonAbilities.fail(p, "Not enough room");
            return;
        }
        double vr = s().d("wisp.viewRange");
        if (o.distanceToSqr(bodyPos(p)) > vr * vr) {
            MoonAbilities.fail(p, "Too far from your body");
            return;
        }
        if (s().b("wisp.respectProtection") && !MoonAbilities.canPlace(p, hit.getBlockPos().relative(face))) {
            MoonAbilities.fail(p, "You can't place an observer here");
            return;
        }
        o.owner = p.getUUID();
        o.number = freeNumber(mine, max);
        mine.add(o);
        p.level.addFreshEntity(o);
        p.level.playSound(null, c.x, c.y, c.z, SoundEvents.SOUL_SAND_PLACE, SoundCategory.PLAYERS, 0.7F, 1.4F);
        info(p, "Observer " + o.number + " placed (" + mine.size() + "/" + max + ")");
        sendState(p);
    }

    private static int freeNumber(List<ObserverEntity> mine, int max) {
        for (int n = 1; n <= max; n++) {
            boolean used = false;
            for (ObserverEntity o : mine) used |= o.number == n;
            if (!used) return n;
        }
        return mine.size() + 1;
    }

    private static void dismiss(ServerPlayerEntity p, int number) {
        List<ObserverEntity> mine = OBSERVERS.get(p.getUUID());
        if (mine == null) return;
        for (Iterator<ObserverEntity> it = mine.iterator(); it.hasNext(); ) {
            ObserverEntity o = it.next();
            if (o.number == number) {
                o.remove();
                it.remove();
                info(p, "Observer " + number + " dismissed");
            }
        }
    }

    private static void startView(ServerPlayerEntity p, int number) {
        ObserverEntity target = null;
        for (ObserverEntity o : observers(p)) if (o.number == number) target = o;
        if (target == null || !target.isAlive()) {
            MoonAbilities.fail(p, "That observer is gone");
            return;
        }
        if (!canView(p, target)) {
            MoonAbilities.fail(p, "Observer out of range");
            return;
        }
        View v = new View();
        v.observer = target;
        v.anchor = feet(p);
        VIEWS.put(p.getUUID(), v);
        sendState(p);
    }

    /** Called by the observer itself when a hit destroys it. */
    public static void observerDestroyed(ObserverEntity o, DamageSource source) {
        if (o.owner == null) return;
        List<ObserverEntity> l = OBSERVERS.get(o.owner);
        if (l != null) l.remove(o);
        if (o.level instanceof ServerWorld) ((ServerWorld) o.level).sendParticles(ParticleTypes.SOUL, o.getX(), o.getY() + 0.2, o.getZ(), 6, 0.1, 0.1, 0.1, 0.02);
        o.level.playSound(null, o.getX(), o.getY(), o.getZ(), SoundEvents.SOUL_ESCAPE, SoundCategory.PLAYERS, 0.6F, 0.8F);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayerEntity owner = server == null ? null : player(server, o.owner);
        if (owner != null) {
            info(owner, "Observer " + o.number + " was destroyed");
            View v = VIEWS.get(owner.getUUID());
            if (v != null && v.observer == o) end(owner, "The observer was destroyed", false);
            else sendState(owner);
        }
    }

    /** Remove a player's observers (all of them, or only those in another dimension than {@code keepIn}). */
    static void removeObservers(UUID owner, @Nullable net.minecraft.util.RegistryKey<net.minecraft.world.World> keepIn) {
        List<ObserverEntity> l = OBSERVERS.get(owner);
        if (l == null) return;
        for (Iterator<ObserverEntity> it = l.iterator(); it.hasNext(); ) {
            ObserverEntity o = it.next();
            if (keepIn != null && o.level.dimension().equals(keepIn)) continue;
            o.remove();
            it.remove();
        }
        if (l.isEmpty()) OBSERVERS.remove(owner);
    }

    // ---- sync -------------------------------------------------------------------------------------------------------

    static void sendState(ServerPlayerEntity p) {
        Pilot pi = PILOTS.get(p.getUUID());
        View v = VIEWS.get(p.getUUID());
        int count = observers(p).size(), max = s().i("wisp.maxObservers");
        int cd = MoonAbilities.cooldownLeft(p, MoonType.WISP);
        if (pi != null) Network.send(p, new WispStatePacket(WispStatePacket.PILOT, pi.wisp.getId(), 0, 0, 0, count, max, cd));
        else if (v != null) Network.send(p, new WispStatePacket(WispStatePacket.VIEW, v.observer.getId(), v.observer.yRot, v.observer.xRot,
                v.observer.number, count, max, cd));
        else Network.send(p, new WispStatePacket(WispStatePacket.NONE, -1, 0, 0, 0, count, max, cd));
    }

    // ---- cleanup and protection -------------------------------------------------------------------------------------

    /** Death, logout, dimension change: back to the body, and the player's observers go too. */
    static void cleanup(ServerPlayerEntity p, String why) {
        end(p, null, true);
        removeObservers(p.getUUID(), null);
    }

    public static int clearAll() {
        int n = 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        for (UUID id : new ArrayList<>(PILOTS.keySet())) {
            ServerPlayerEntity p = server == null ? null : player(server, id);
            Pilot pi = PILOTS.get(id);
            if (p != null) end(p, "The wisp fades", false);
            else {
                PILOTS.remove(id);
                if (pi.wisp != null) pi.wisp.remove();
            }
            n++;
        }
        for (UUID id : new ArrayList<>(VIEWS.keySet())) {
            ServerPlayerEntity p = server == null ? null : player(server, id);
            if (p != null) end(p, null, false);
            else VIEWS.remove(id);
        }
        for (List<ObserverEntity> l : OBSERVERS.values()) {
            for (ObserverEntity o : l) {
                o.remove();
                n++;
            }
        }
        OBSERVERS.clear();
        return n;
    }

    public static List<String> describe(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, Pilot> e : PILOTS.entrySet()) {
            ServerPlayerEntity p = player(server, e.getKey());
            WispEntity w = e.getValue().wisp;
            out.add((p != null ? p.getScoreboardName() : e.getKey().toString()) + " is piloting a wisp at "
                    + (int) Math.floor(w.getX()) + " " + (int) Math.floor(w.getY()) + " " + (int) Math.floor(w.getZ())
                    + (p != null ? String.format(" (%.1f m from body)", w.position().distanceTo(feet(p))) : ""));
        }
        for (Map.Entry<UUID, View> e : VIEWS.entrySet()) {
            ServerPlayerEntity p = player(server, e.getKey());
            out.add((p != null ? p.getScoreboardName() : e.getKey().toString()) + " is viewing observer " + e.getValue().observer.number);
        }
        for (Map.Entry<UUID, List<ObserverEntity>> e : OBSERVERS.entrySet()) {
            ServerPlayerEntity p = player(server, e.getKey());
            for (ObserverEntity o : e.getValue()) {
                out.add("Observer " + o.number + " of " + (p != null ? p.getScoreboardName() : e.getKey().toString()) + " in "
                        + o.level.dimension().location() + " at " + (int) Math.floor(o.getX()) + " " + (int) Math.floor(o.getY()) + " "
                        + (int) Math.floor(o.getZ()));
            }
        }
        return out;
    }

    /** Damage to the body immediately returns the camera to it. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHurt(LivingHurtEvent event) {
        if (event.getEntityLiving() instanceof ServerPlayerEntity && inControl((ServerPlayerEntity) event.getEntityLiving())) {
            ServerPlayerEntity p = (ServerPlayerEntity) event.getEntityLiving();
            end(p, "You were hurt", piloting(p));
        }
    }

    /** The body can't attack, use items, or interact while the camera is elsewhere. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent event) {
        if (event.isCancelable() && !event.getWorld().isClientSide && inControl(event.getPlayer())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (!event.getPlayer().level.isClientSide && inControl(event.getPlayer())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (inControl(event.getPlayer())) event.setCanceled(true);
    }

    /** Nothing may ride, push or otherwise use the wisp or an observer. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Entity t = event.getTarget();
        if (t instanceof WispEntity || t instanceof ObserverEntity) event.setCanceled(true);
    }
}
