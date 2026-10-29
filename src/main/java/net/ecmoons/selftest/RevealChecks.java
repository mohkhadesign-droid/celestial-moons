package net.ecmoons.selftest;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.feature.RevealingFeatures;
import net.ecmoons.feature.reveal.BindingGaze;
import net.ecmoons.feature.reveal.MoonGaze;
import net.ecmoons.feature.reveal.ReadYourEnemy;
import net.ecmoons.feature.reveal.RevealState;
import net.ecmoons.feature.reveal.WorldNotices;
import net.ecmoons.net.InspectPacket;
import net.ecmoons.net.RevealPacket;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.monster.ZombieEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.Effects;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.GameType;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Dedicated-server checks for the Revealing Moon update: moon direction and open-sky test, Binding Gaze build-up and
 * release, True Exposure (restoring only real, unexpired potion invisibility), reveal timers and every cleanup path,
 * Read Your Enemy's server validation, investigations (caps, deterministic choice, existing targets kept, cleanup)
 * and the phase arrangement. Fake players are not in the world's player list, so the parts are driven directly.
 */
final class RevealChecks {
    private RevealChecks() {}

    private static FakePlayer fake(ServerWorld w, String name) {
        FakePlayer p = FakePlayerFactory.get(w, new GameProfile(UUID.randomUUID(), name));
        // a detached connection, as on a real server player (vanilla sends effect packets to it; ours skip fakes)
        new net.minecraft.network.play.ServerPlayNetHandler(w.getServer(),
                new net.minecraft.network.NetworkManager(net.minecraft.network.PacketDirection.SERVERBOUND), p);
        p.gameMode.setGameModeForPlayer(GameType.SURVIVAL);
        p.removeAllEffects();
        p.setInvisible(false);
        return p;
    }

    private static void look(FakePlayer p, Vector3d dir) {
        dir = dir.normalize();
        p.xRot = (float) -Math.toDegrees(Math.asin(dir.y));
        p.yRot = (float) -Math.toDegrees(Math.atan2(dir.x, dir.z));
        p.xRotO = p.xRot;
        p.yRotO = p.yRot;
        p.yHeadRot = p.yRot;
    }

    private static void fill(ServerWorld w, int x0, int y, int z0, int x1, int z1, net.minecraft.block.BlockState s) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) w.setBlock(new BlockPos(x, y, z), s, 2);
    }

    static void staticChecks(ServerWorld w, BiConsumer<String, Boolean> check) {
        // ---- moon direction: exactly where the sky renderer draws it ----------------------------------------------
        Vector3d mid = MoonGaze.moonDirection(0.5f), noon = MoonGaze.moonDirection(0f), set = MoonGaze.moonDirection(0.25f);
        check.accept("revealing: the moon is overhead at midnight (" + mid + ") and below at noon (" + noon + ")", mid.y > 0.999 && noon.y < -0.999);
        check.accept("revealing: the moon is on the horizon at time-of-day 0.25 (" + set + ")", Math.abs(set.y) < 1e-3);
        check.accept("revealing: phase split - a 5 s countdown gives 3 s of active scanning, a 2 s countdown at most 1 s",
                activeTicks(100) == 60 && activeTicks(40) == 20);

        BlockPos spawn = w.getSharedSpawnPos();
        int bx = spawn.getX() + 20, bz = spawn.getZ() - 20;
        w.getChunk(bx >> 4, bz >> 4);
        int y = w.getHeight(Heightmap.Type.MOTION_BLOCKING, bx, bz) + 30;
        Vector3d moon = new Vector3d(0.25, 0.92, 0.3).normalize(); // a synthetic moon high in the sky
        FakePlayer starer = fake(w, "ecm_starer");
        starer.moveTo(bx + 0.5, y, bz + 0.5, 0, 0);
        look(starer, moon);
        check.accept("revealing: looking at the moon's centre counts (angle " + MoonGaze.angleToMoon(starer, moon) + ")",
                MoonGaze.lookingAtMoon(starer, moon, 5.0));
        look(starer, new Vector3d(moon.x + 0.2, moon.y, moon.z)); // ~11 degrees off
        check.accept("revealing: aiming vaguely upward does not (angle " + MoonGaze.angleToMoon(starer, moon) + ")",
                !MoonGaze.lookingAtMoon(starer, moon, 5.0));
        look(starer, moon);
        check.accept("revealing: open sky towards the moon", BindingGaze.gazing(starer, moon, 5.0));
        int ry = y + 4;
        fill(w, bx - 4, ry, bz - 4, bx + 6, bz + 6, Blocks.STONE.defaultBlockState());
        check.accept("revealing: a solid roof blocks the gaze", !BindingGaze.gazing(starer, moon, 5.0));
        fill(w, bx - 4, ry, bz - 4, bx + 6, bz + 6, Blocks.GLASS.defaultBlockState());
        check.accept("revealing: a glass roof does not (you can see the eye through it)", BindingGaze.gazing(starer, moon, 5.0));
        fill(w, bx - 4, ry, bz - 4, bx + 6, bz + 6, Blocks.OAK_LEAVES.defaultBlockState());
        check.accept("revealing: a leaf canopy blocks the gaze", !BindingGaze.gazing(starer, moon, 5.0));
        fill(w, bx - 4, ry, bz - 4, bx + 6, bz + 6, Blocks.AIR.defaultBlockState());
        check.accept("revealing: a moon below the horizon can never bind", !MoonGaze.skyOpen(w, starer.getEyePosition(1f), new Vector3d(0, -1, 0), 64));

        // ---- Binding Gaze: build-up, reset, release, no accumulation -----------------------------------------------
        int need = 40;
        for (int i = 0; i < need - 1; i++) BindingGaze.step(starer, true, need, 901);
        boolean notYet = !BindingGaze.isBound(starer);
        BindingGaze.step(starer, false, need, 901);
        boolean reset = BindingGaze.stareTicks(starer) == 0;
        for (int i = 0; i < need - 1; i++) BindingGaze.step(starer, true, need, 901);
        boolean stillNot = !BindingGaze.isBound(starer);
        BindingGaze.step(starer, true, need, 901);
        check.accept("binding gaze: 1.95 s of staring is not enough, looking away resets, 2 s binds",
                notYet && reset && stillNot && BindingGaze.isBound(starer));
        int before = BindingGaze.bindsStarted;
        for (int i = 0; i < 100; i++) BindingGaze.step(starer, true, need, 901);
        check.accept("binding gaze: staring on does not stack or re-bind", BindingGaze.isBound(starer) && BindingGaze.bindsStarted == before
                && BindingGaze.stareTicks(starer) == need);
        Vector3d posBefore = starer.position(), motion = new Vector3d(0.3, -0.6, 0.1);
        starer.setDeltaMovement(motion);
        check.accept("binding gaze: the bind never touches position or velocity (falling and knockback continue)",
                starer.position().equals(posBefore) && starer.getDeltaMovement().equals(motion) && starer.getActiveEffects().isEmpty());
        BindingGaze.step(starer, false, need, 901);
        check.accept("binding gaze: looking away releases immediately", !BindingGaze.isBound(starer));
        for (int i = 0; i < need; i++) BindingGaze.step(starer, true, need, 901);
        BindingGaze.releaseAll(Collections.singletonList(starer));
        check.accept("binding gaze: scan completion releases every bind", !BindingGaze.isBound(starer) && BindingGaze.stareTicks(starer) == 0);
        for (int i = 0; i < need; i++) BindingGaze.step(starer, true, need, 901);
        starer.gameMode.setGameModeForPlayer(GameType.SPECTATOR);
        BindingGaze.tickActive(w, Collections.singletonList(starer), 901);
        check.accept("binding gaze: becoming ineligible (spectator) releases", !BindingGaze.isBound(starer));
        starer.gameMode.setGameModeForPlayer(GameType.SURVIVAL);
        BindingGaze.forget(starer.getUUID());

        // ---- True Exposure ---------------------------------------------------------------------------------------
        long now = w.getServer().getTickCount();
        FakePlayer ghost = fake(w, "ecm_ghost");
        ghost.moveTo(bx + 0.5, y, bz + 3.5, 0, 0);
        ghost.addEffect(new EffectInstance(Effects.INVISIBILITY, 600));
        ghost.addEffect(new EffectInstance(Effects.DAMAGE_BOOST, 600, 1));
        ghost.baseTick();
        check.accept("true exposure: vanilla invisibility hides before any reveal", ghost.isInvisible());
        double hiddenVis = ghost.getVisibilityPercent(null);
        RevealState.reveal(ghost, 902, now, 100);
        int dur = ghost.getEffect(Effects.INVISIBILITY).getDuration();
        check.accept("true exposure: revealed -> visible at once, the potion kept with its time (" + dur + ")",
                !ghost.isInvisible() && dur >= 595 && ghost.hasEffect(Effects.DAMAGE_BOOST));
        check.accept("true exposure: server detection agrees (visibility " + ghost.getVisibilityPercent(null) + " vs " + hiddenVis + " hidden)",
                ghost.getVisibilityPercent(null) > hiddenVis);
        ghost.baseTick();
        check.accept("true exposure: the potion keeps counting down while suppressed", ghost.getEffect(Effects.INVISIBILITY).getDuration() < dur);
        ghost.removeEffect(Effects.INVISIBILITY);
        ghost.addEffect(new EffectInstance(Effects.INVISIBILITY, 400)); // drinking another potion while exposed
        ghost.baseTick();
        check.accept("true exposure: a potion drunk while exposed does not hide", !ghost.isInvisible() && ghost.hasEffect(Effects.INVISIBILITY));
        RevealState.end(ghost, RevealState.EndReason.EXPIRED);
        check.accept("true exposure: at expiry invisibility resumes because the effect still runs",
                ghost.isInvisible() && ghost.getEffect(Effects.INVISIBILITY).getDuration() > 300 && ghost.hasEffect(Effects.DAMAGE_BOOST));
        // milk while exposed
        RevealState.reveal(ghost, 903, now, 100);
        ghost.removeAllEffects();
        ghost.baseTick();
        RevealState.end(ghost, RevealState.EndReason.EXPIRED);
        check.accept("true exposure: milk-cleared invisibility never comes back", !ghost.isInvisible());
        // the potion runs out during the reveal
        ghost.addEffect(new EffectInstance(Effects.INVISIBILITY, 2));
        ghost.baseTick();
        RevealState.reveal(ghost, 904, now, 100);
        for (int i = 0; i < 4; i++) ghost.baseTick();
        RevealState.end(ghost, RevealState.EndReason.EXPIRED);
        check.accept("true exposure: an invisibility that expired during the reveal is not restored",
                !ghost.isInvisible() && !ghost.hasEffect(Effects.INVISIBILITY));
        // invisibility owned by another system (no potion) is left alone both ways
        ghost.setInvisible(true);
        RevealState.reveal(ghost, 905, now, 100);
        boolean keptDuring = ghost.isInvisible();
        RevealState.end(ghost, RevealState.EndReason.EXPIRED);
        check.accept("true exposure: non-potion invisibility from another system is never overwritten", keptDuring && ghost.isInvisible());
        ghost.setInvisible(false);

        // ---- reveal timers and cleanup paths -----------------------------------------------------------------------
        FakePlayer a = fake(w, "ecm_revealed");
        a.moveTo(bx + 2.5, y, bz + 0.5, 0, 0);
        RevealState.reveal(a, 906, now, 160);
        RevealState.reveal(a, 907, now + 20, 160); // refresh: the authoritative new timer, never stacked
        check.accept("reveal timer: a refresh replaces the timer (no stacking)",
                RevealState.get(a).scanId == 907 && RevealState.get(a).end == now + 180 && RevealState.remaining(a, now + 20) == 160);
        RevealState.tick(Collections.singletonList(a), now + 179);
        boolean alive = RevealState.isRevealed(a);
        RevealState.tick(Collections.singletonList(a), now + 180);
        check.accept("reveal timer: ends exactly at its own expiry", alive && !RevealState.isRevealed(a)
                && RevealState.LAST_END.get(a.getUUID()) == RevealState.EndReason.EXPIRED);
        RevealState.reveal(a, 908, now, 160);
        a.addTag(RevealingFeatures.EXEMPT_TAG);
        RevealState.tick(Collections.singletonList(a), now + 1);
        a.removeTag(RevealingFeatures.EXEMPT_TAG);
        check.accept("reveal timer: becoming exempt ends it at once", !RevealState.isRevealed(a)
                && RevealState.LAST_END.get(a.getUUID()) == RevealState.EndReason.INELIGIBLE);
        FakePlayer b = fake(w, "ecm_revealed2");
        RevealState.reveal(a, 909, now, 160);
        RevealState.reveal(b, 909, now, 60);
        RevealState.tick(Arrays.asList(a, b), now + 60);
        check.accept("reveal timer: different players' timers end independently", RevealState.isRevealed(a) && !RevealState.isRevealed(b));
        for (int i = 0; i < need; i++) BindingGaze.step(a, true, need, 909);
        RevealState.endAll(Arrays.asList(a, b), w.dimension(), RevealState.EndReason.MOON_ENDED);
        check.accept("reveal timer: the moon ending (same path for admin stop) clears reveals and binds",
                !RevealState.isRevealed(a) && !BindingGaze.isBound(a) && RevealState.LAST_END.get(a.getUUID()) == RevealState.EndReason.MOON_ENDED);
        RevealState.reveal(a, 910, now, 160);
        RevealState.forget(a);
        check.accept("reveal timer: logging out drops the state (nothing is saved on the player)", !RevealState.isRevealed(a)
                && !a.getPersistentData().contains("ecmoons") && !a.getTags().contains("ecmoons_revealed"));

        // ---- Read Your Enemy: server validation ---------------------------------------------------------------------
        FakePlayer obs = fake(w, "ecm_observer"), tgt = fake(w, "ecm_target");
        obs.moveTo(bx + 0.5, y, bz + 0.5, 0, 0);
        tgt.moveTo(bx + 0.5, y, bz + 10.5, 0, 0);
        tgt.setHealth(7f);
        tgt.setAbsorptionAmount(4f);
        tgt.addEffect(new EffectInstance(Effects.DAMAGE_BOOST, 900, 1));
        tgt.addEffect(new EffectInstance(Effects.MOVEMENT_SPEED, 300, 0));
        look(obs, tgt.getBoundingBox().getCenter().subtract(obs.getEyePosition(1f)));
        ReadYourEnemy.TEST_TARGETS.put(tgt.getId(), tgt);
        check.accept("read your enemy: an unrevealed observer gets nothing", ReadYourEnemy.check(obs, tgt) == ReadYourEnemy.Check.NOT_REVEALED);
        RevealState.reveal(obs, 911, now, 400);
        check.accept("read your enemy: revealed, aiming, in range, clear view -> allowed (" + ReadYourEnemy.check(obs, tgt) + ")",
                ReadYourEnemy.check(obs, tgt) == ReadYourEnemy.Check.OK);
        look(obs, new Vector3d(1, 0, 0));
        check.accept("read your enemy: looking away fails", ReadYourEnemy.check(obs, tgt) == ReadYourEnemy.Check.NOT_AIMED);
        look(obs, tgt.getBoundingBox().getCenter().subtract(obs.getEyePosition(1f)));
        for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 3; dy++) w.setBlock(new BlockPos(bx + dx, y + dy, bz + 5), Blocks.STONE.defaultBlockState(), 2);
        check.accept("read your enemy: no reading through walls", ReadYourEnemy.check(obs, tgt) == ReadYourEnemy.Check.NO_LINE_OF_SIGHT);
        for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 3; dy++) w.setBlock(new BlockPos(bx + dx, y + dy, bz + 5), Blocks.AIR.defaultBlockState(), 2);
        tgt.gameMode.setGameModeForPlayer(GameType.SPECTATOR);
        check.accept("read your enemy: excluded targets (spectators) are never readable", ReadYourEnemy.check(obs, tgt) == ReadYourEnemy.Check.TARGET_INELIGIBLE);
        tgt.gameMode.setGameModeForPlayer(GameType.SURVIVAL);
        tgt.moveTo(bx + 0.5, y, bz + 40.5, 0, 0);
        look(obs, tgt.getBoundingBox().getCenter().subtract(obs.getEyePosition(1f)));
        check.accept("read your enemy: beyond 32 blocks fails", ReadYourEnemy.check(obs, tgt) == ReadYourEnemy.Check.OUT_OF_RANGE);
        tgt.moveTo(bx + 0.5, y, bz + 10.5, 0, 0);
        look(obs, tgt.getBoundingBox().getCenter().subtract(obs.getEyePosition(1f)));
        ReadYourEnemy.LAST.remove(obs.getUUID());
        ReadYourEnemy.onAim(obs, tgt.getId());
        List<net.minecraft.entity.player.ServerPlayerEntity> obsList = Collections.singletonList(obs);
        for (int t = 1; t <= 19; t++) ReadYourEnemy.tick(obsList, now + t);
        boolean early = !ReadYourEnemy.acquired(obs) && !ReadYourEnemy.LAST.containsKey(obs.getUUID());
        ReadYourEnemy.tick(obsList, now + 20);
        InspectPacket info = ReadYourEnemy.LAST.get(obs.getUUID());
        check.accept("read your enemy: nothing before one second of aim, then the panel data", early && ReadYourEnemy.acquired(obs) && info != null
                && info.targetId == tgt.getId());
        check.accept("read your enemy: health, max health, absorption apart, effects with amplifier and time (" + (info == null ? "none"
                        : info.health + "/" + info.maxHealth + " +" + info.absorption + " " + info.effects.size() + " effects") + ")",
                info != null && info.health == 7f && info.maxHealth == 20f && info.absorption == 4f && info.effects.size() == 2
                        && info.effects.get(0).id.toString().equals("minecraft:strength") && info.effects.get(0).amplifier == 1
                        && info.effects.get(0).duration > 800);
        int sentBefore = ReadYourEnemy.sent;
        for (int t = 21; t <= 40; t++) ReadYourEnemy.tick(obsList, now + t);
        check.accept("read your enemy: updates at the bounded rate (4 per second -> " + (ReadYourEnemy.sent - sentBefore) + " in 20 ticks)",
                ReadYourEnemy.sent - sentBefore == 4);
        look(obs, new Vector3d(1, 0, 0));
        ReadYourEnemy.tick(obsList, now + 41);
        InspectPacket cleared = ReadYourEnemy.LAST.get(obs.getUUID());
        check.accept("read your enemy: looking away hides the panel at once and resets", cleared != null && cleared.targetId < 0 && !ReadYourEnemy.acquired(obs));
        look(obs, tgt.getBoundingBox().getCenter().subtract(obs.getEyePosition(1f)));
        for (int t = 42; t <= 70; t++) ReadYourEnemy.tick(obsList, now + t);
        RevealState.end(obs, RevealState.EndReason.EXPIRED);
        InspectPacket afterEnd = ReadYourEnemy.LAST.get(obs.getUUID());
        check.accept("read your enemy: the observer's reveal ending clears the panel", afterEnd != null && afterEnd.targetId < 0 && !ReadYourEnemy.acquired(obs));
        ReadYourEnemy.onAim(obs, tgt.getId());
        ReadYourEnemy.tick(obsList, now + 80);
        check.accept("read your enemy: aim proposals from an unrevealed client are ignored", !ReadYourEnemy.acquired(obs) && ReadYourEnemy.LAST.get(obs.getUUID()).targetId < 0);
        ReadYourEnemy.TEST_TARGETS.clear();
        ReadYourEnemy.forget(obs.getUUID());

        // ---- packets round-trip -------------------------------------------------------------------------------------
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        RevealPacket.encode(RevealPacket.sync(12, 3456789L, 40, 100, 3456700L), buf);
        RevealPacket rp = RevealPacket.decode(buf);
        check.accept("revealing: SYNC packet round trip (scan id, game times, phase lengths)", rp.stage == RevealPacket.SYNC && rp.scanId == 12
                && rp.time == 3456789L && rp.time2 == 3456700L && rp.a == 40 && rp.b == 100);
        buf = new PacketBuffer(Unpooled.buffer());
        InspectPacket.encode(info != null ? info : InspectPacket.clear(), buf);
        InspectPacket ip = InspectPacket.decode(buf);
        check.accept("read your enemy: packet round trip", info != null && ip.targetId == info.targetId && ip.name.equals(info.name)
                && ip.effects.size() == info.effects.size() && ip.absorption == info.absorption);
        EcMoons.LOGGER.info("[ECMOONS-SELFTEST] revealing static checks done");
    }

    private static int activeTicks(int countdown) {
        return RevealingFeatures.activeTicks(countdown);
    }

    // ---- The World Notices You --------------------------------------------------------------------------------------

    private static final List<MobEntity> MOBS = new ArrayList<>();
    private static FakePlayer scouted;
    private static Vector3d spot;
    private static ZombieEntity fighter;
    private static double startDist;

    static void investigateSetup(ServerWorld w, BiConsumer<String, Boolean> check) {
        BlockPos sp = w.getSharedSpawnPos();
        int x = sp.getX() - 24, z = sp.getZ() + 24;
        w.getChunk(x >> 4, z >> 4);
        int y = w.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        // a flat stone pad with a ceiling of glass, so the test mobs neither burn nor wander off a cliff
        for (int dx = -20; dx <= 20; dx++) for (int dz = -20; dz <= 20; dz++) {
            w.setBlock(new BlockPos(x + dx, y - 1, z + dz), Blocks.STONE.defaultBlockState(), 2);
            for (int dy = 0; dy <= 3; dy++) w.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.defaultBlockState(), 2);
            w.setBlock(new BlockPos(x + dx, y + 4, z + dz), Blocks.BLACK_STAINED_GLASS.defaultBlockState(), 2);
        }
        for (MobEntity old : w.getEntitiesOfClass(MobEntity.class, new net.minecraft.util.math.AxisAlignedBB(x - 40, y - 20, z - 40, x + 40, y + 20, z + 40))) old.remove();
        scouted = fake(w, "ecm_scouted");
        scouted.moveTo(x + 0.5, y, z + 0.5, 0, 0);
        spot = scouted.position();
        MOBS.clear();
        // eight idle zombies at increasing distance, one zombie already fighting, a cow and a zombie 30 blocks away
        for (int i = 0; i < 8; i++) MOBS.add(spawn(w, EntityType.ZOMBIE, x + 6 + i * 1.5, y, z + (i % 2 == 0 ? 3 : -3)));
        fighter = (ZombieEntity) spawn(w, EntityType.ZOMBIE, x - 4.5, y, z + 0.5);
        CowEntity cow = (CowEntity) spawn(w, EntityType.COW, x - 3.5, y, z - 4.5);
        MobEntity far = spawn(w, EntityType.ZOMBIE, x + 0.5, y, z + 30.5);
        FakePlayer other = fake(w, "ecm_victim");
        other.moveTo(x - 8.5, y, z + 0.5, 0, 0);
        fighter.setTarget(other);
        int started = WorldNotices.started;
        int n = WorldNotices.onScan(w, Collections.singletonList(scouted), w.getServer().getTickCount());
        List<MobEntity> nearest = new ArrayList<>(MOBS.subList(0, 6));
        boolean chosen = true;
        for (MobEntity m : nearest) chosen &= WorldNotices.investigating(m) && spot.equals(WorldNotices.spotOf(m));
        check.accept("world notices: at most 6 investigators per revealed player (" + n + ")", n == 6 && WorldNotices.started - started == 6);
        check.accept("world notices: the 6 nearest idle hostiles are chosen deterministically", chosen
                && !WorldNotices.investigating(MOBS.get(6)) && !WorldNotices.investigating(MOBS.get(7)));
        check.accept("world notices: a mob already fighting keeps its target and is not used", !WorldNotices.investigating(fighter) && fighter.getTarget() == other);
        check.accept("world notices: neutral/passive mobs and mobs out of range are never used", !WorldNotices.investigating(cow) && !WorldNotices.investigating(far));
        check.accept("world notices: no attack target is set on investigators", MOBS.get(0).getTarget() == null);
        int again = WorldNotices.onScan(w, Collections.singletonList(scouted), w.getServer().getTickCount());
        check.accept("world notices: one assignment per mob (a second scan adds only the 2 unassigned)", again == 2);
        startDist = 0;
        for (int i = 0; i < 6; i++) startDist += MOBS.get(i).position().distanceTo(spot);
        MOBS.add(fighter);
        MOBS.add(cow);
        MOBS.add(far);
        scouted.moveTo(scouted.getX() + 15, scouted.getY(), scouted.getZ(), 0, 0); // the player moves away: no tracking
    }

    private static MobEntity spawn(ServerWorld w, EntityType<? extends MobEntity> type, double x, double y, double z) {
        MobEntity m = type.create(w);
        m.moveTo(x, y, z, 0, 0);
        m.setPersistenceRequired();
        w.addFreshEntity(m);
        return m;
    }

    static void investigateWalk(ServerWorld w, BiConsumer<String, Boolean> check) {
        double d = 0;
        for (int i = 0; i < 6; i++) d += MOBS.get(i).position().distanceTo(spot);
        check.accept("world notices: investigators walk towards the OLD spot with ordinary navigation (total distance "
                        + String.format("%.1f -> %.1f", startDist, d) + "), the spot is not updated after the player moved",
                d < startDist - 4.0 && spot.equals(WorldNotices.spotOf(MOBS.get(5))));
        // a mob that finds a target on its own drops the investigation and keeps that target
        MobEntity taker = MOBS.get(0);
        FakePlayer victim = fake(w, "ecm_victim2");
        victim.moveTo(taker.getX() + 2, taker.getY(), taker.getZ(), 0, 0);
        taker.setTarget(victim);
        long now = w.getServer().getTickCount();
        WorldNotices.tick(w, now);
        check.accept("world notices: normal targeting takes over (the investigation ends, the target stays)",
                !WorldNotices.investigating(taker) && taker.getTarget() == victim);
        taker.setTarget(null);
        WorldNotices.tick(w, now + 400);
        boolean stopped = true;
        for (int i = 1; i < 8; i++) stopped &= !WorldNotices.investigating(MOBS.get(i));
        check.accept("world notices: investigations end after the time limit and only our path is stopped", stopped && WorldNotices.active(w) == 0
                && MOBS.get(1).isAlive() && !MOBS.get(1).isNoAi());
        // moon end path
        WorldNotices.onScan(w, Collections.singletonList(scouted), now);
        WorldNotices.endAll(w);
        check.accept("world notices: the moon ending removes every investigation", WorldNotices.active(w) == 0);
        for (MobEntity mob : MOBS) mob.remove();
        MOBS.clear();
    }

    // ---- live cycle with the moon up ---------------------------------------------------------------------------------

    private static int scanBefore;

    static void cycleWarn(ServerWorld w, BiConsumer<String, Boolean> check) {
        check.accept("revealing cycle: the Revealing Moon is active", Moons.active(w) == MoonType.REVEALING);
        scanBefore = RevealingFeatures.lastScanId;
    }

    static void cycleOpening(ServerWorld w, BiConsumer<String, Boolean> check) {
        check.accept("revealing cycle: the eye is opening 1 s into the countdown (" + RevealingFeatures.phase(w) + ")",
                "opening".equals(RevealingFeatures.phase(w)));
    }

    static void cycleActive(ServerWorld w, BiConsumer<String, Boolean> check) {
        check.accept("revealing cycle: active scanning in the last 3 s of the countdown (" + RevealingFeatures.phase(w) + ")",
                "active".equals(RevealingFeatures.phase(w)) && RevealingFeatures.lastScanId == scanBefore);
    }

    static void cycleComplete(ServerWorld w, BiConsumer<String, Boolean> check) {
        check.accept("revealing cycle: completion at the end of the countdown (scan " + RevealingFeatures.lastScanId + ", phase "
                + RevealingFeatures.phase(w) + ")", RevealingFeatures.lastScanId > scanBefore && "idle".equals(RevealingFeatures.phase(w)));
    }

    static void cycleEnded(ServerWorld w, BiConsumer<String, Boolean> check) {
        check.accept("revealing cycle: ending the moon removes the cycle and all reveal state ("
                + RevealingFeatures.describe(w) + ")", "none".equals(RevealingFeatures.phase(w)) && RevealState.count() == 0
                && WorldNotices.active(w) == 0);
    }
}
