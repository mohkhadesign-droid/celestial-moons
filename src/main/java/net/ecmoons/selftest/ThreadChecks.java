package net.ecmoons.selftest;

import com.mojang.authlib.GameProfile;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.feature.MoonTeams;
import net.ecmoons.feature.thread.GuardThread;
import net.ecmoons.feature.thread.Knotbind;
import net.ecmoons.feature.thread.Lifeline;
import net.ecmoons.feature.thread.ThreadEvent;
import net.ecmoons.feature.thread.ThreadLinks;
import net.ecmoons.feature.thread.ThreadMoon;
import net.ecmoons.feature.thread.ThreadNet;
import net.ecmoons.feature.thread.ThreadReactions;
import net.ecmoons.net.ThreadPacket;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.GameType;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Dedicated-server checks for the Thread Moon update (the moon is up). Fake players are not in the world's player
 * list and cannot be hurt for real, so the rules and the damage/death hooks are driven directly with explicit ticks;
 * Knotbind's projectile is fired at real mobs.
 */
final class ThreadChecks {
    private ThreadChecks() {}

    /**
     * A real ServerPlayerEntity that is not in the world (ServerPlayerEntity refuses PvP and reports 0,0,0 as its position,
     * which would hide exactly the rules tested here), with a detached connection.
     */
    private static ServerPlayerEntity fake(ServerWorld w, String name) {
        ServerPlayerEntity p = new ServerPlayerEntity(w.getServer(), w, new GameProfile(UUID.randomUUID(), name),
                new net.minecraft.server.management.PlayerInteractionManager(w));
        new net.minecraft.network.play.ServerPlayNetHandler(w.getServer(),
                new net.minecraft.network.NetworkManager(net.minecraft.network.PacketDirection.SERVERBOUND), p);
        p.gameMode.setGameModeForPlayer(GameType.SURVIVAL);
        p.removeAllEffects();
        p.setHealth(20f);
        Lifeline.TEST_PLAYERS.put(p.getId(), p);
        return p;
    }

    private static void look(ServerPlayerEntity p, Vector3d at) {
        Vector3d dir = at.subtract(p.getEyePosition(1f)).normalize();
        p.xRot = (float) -Math.toDegrees(Math.asin(dir.y));
        p.yRot = (float) -Math.toDegrees(Math.atan2(dir.x, dir.z));
        p.xRotO = p.xRot;
        p.yRotO = p.yRot;
        p.yHeadRot = p.yRot;
    }

    private static Vector3d centre(ServerPlayerEntity p) {
        return new Vector3d(p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ());
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1e-3f;
    }

    static boolean cancelHeal, doubleHeal;

    static void register() {
        MinecraftForge.EVENT_BUS.addListener((LivingHealEvent e) -> {
            if (cancelHeal) e.setCanceled(true);
            else if (doubleHeal) e.setAmount(e.getAmount() * 2f);
        });
    }

    static void checks(ServerWorld w, BiConsumer<String, Boolean> check) {
        check.accept("thread: the Thread Moon is up for these checks", Moons.is(w, MoonType.THREAD));
        MoonTeams teams = MoonTeams.get(w.getServer());
        teams.create("ecm_red", "Red Test");
        teams.create("ecm_blue", "Blue Test");
        BlockPos sp = w.getSharedSpawnPos();
        int x = sp.getX() + 30, z = sp.getZ() + 30;
        w.getChunk(x >> 4, z >> 4);
        int y = w.getHeight(Heightmap.Type.MOTION_BLOCKING, x, z) + 40; // open air: clear lines between everyone
        ServerPlayerEntity a = fake(w, "ecm_t_a"), b = fake(w, "ecm_t_b"), c = fake(w, "ecm_t_c"), d = fake(w, "ecm_t_d");
        ServerPlayerEntity e = fake(w, "ecm_t_e"), f = fake(w, "ecm_t_f");
        for (ServerPlayerEntity p : Arrays.asList(a, b, c, d)) teams.assign(p.getUUID(), p.getScoreboardName(), "ecm_red");
        for (ServerPlayerEntity p : Arrays.asList(e, f)) teams.assign(p.getUUID(), p.getScoreboardName(), "ecm_blue");
        a.moveTo(x + 0.5, y, z + 0.5, 0, 0);
        b.moveTo(x + 3.5, y, z + 0.5, 0, 0);
        c.moveTo(x + 11.5, y, z + 0.5, 0, 0);
        d.moveTo(x + 19.5, y, z + 0.5, 0, 0);
        long now = ThreadMoon.now();
        EcMoons.LOGGER.info("[ECMOONS-SELFTEST] thread diag a-b: " + ThreadLinks.explain(a, b) + " | restrain a->e: " + Knotbind.explainRestrain(a, e)
                + " | a@" + a.getX() + "," + a.getY() + "," + a.getZ() + " b@" + b.getX() + "," + b.getY() + "," + b.getZ() + " level " + a.level.dimension().location());

        // ---- connections: direct only, never transitive -----------------------------------------------------------
        check.accept("thread: teammates 3 blocks apart are directly connected", ThreadLinks.direct(a, b));
        check.accept("thread: connections are not transitive (A-B, B-C 8 blocks, C-D 8 blocks; A-D 19 blocks is not)",
                ThreadLinks.direct(b, c) && ThreadLinks.direct(c, d) && !ThreadLinks.direct(a, d));
        check.accept("thread: enemies are never connected", !ThreadLinks.direct(a, e));

        // ---- Lifeline ---------------------------------------------------------------------------------------------
        b.setHealth(10f);
        look(a, centre(b));
        String why = Lifeline.start(a, b.getId(), now);
        List<ServerPlayerEntity> donors = Collections.singletonList(a);
        int t = 1;
        for (; t < 20; t++) Lifeline.tick(donors, now + t);
        boolean nothingYet = b.getHealth() == 10f && a.getHealth() == 20f;
        for (; t <= 100; t++) Lifeline.tick(donors, now + t);
        check.accept("lifeline: starts on an injured connected teammate (" + why + ")", why == null);
        check.accept("lifeline: nothing moves during the first second of aim", nothingYet);
        check.accept("lifeline: 1 point a second, capped at 4 per activation; donor pays exactly what was gained (A " + a.getHealth()
                + ", B " + b.getHealth() + ")", near(a.getHealth(), 16f) && near(b.getHealth(), 14f) && !Lifeline.channeling(a));
        check.accept("lifeline: 10 s cooldown after an activation that moved health (" + Lifeline.cooldown(a, now + 100) + ")",
                Lifeline.cooldown(a, now + 100) >= 170 && Lifeline.cooldown(a, now + 100) <= 200);
        check.accept("lifeline: the cooldown blocks a new activation", Lifeline.start(a, b.getId(), now + 101) != null);
        // floor
        look(c, centre(b));
        c.setHealth(7f);
        c.moveTo(x + 6.5, y, z + 0.5, 0, 0);
        look(c, centre(b));
        String cw = Lifeline.start(c, b.getId(), now + 200);
        for (t = 201; t <= 300; t++) Lifeline.tick(Collections.singletonList(c), now + t);
        check.accept("lifeline: never below the donor floor of 6 (" + cw + ", C " + c.getHealth() + ", B " + b.getHealth() + ")",
                cw == null && near(c.getHealth(), 6f) && near(b.getHealth(), 15f));
        // one donor per recipient
        c.setHealth(20f);
        d.moveTo(x + 2.5, y, z + 2.5, 0, 0);
        look(c, centre(b));
        look(d, centre(b));
        Lifeline.clear();
        Lifeline.TEST_PLAYERS.put(a.getId(), a); Lifeline.TEST_PLAYERS.put(b.getId(), b); Lifeline.TEST_PLAYERS.put(c.getId(), c);
        Lifeline.TEST_PLAYERS.put(d.getId(), d); Lifeline.TEST_PLAYERS.put(e.getId(), e); Lifeline.TEST_PLAYERS.put(f.getId(), f);
        String first = Lifeline.start(c, b.getId(), now + 400), second = Lifeline.start(d, b.getId(), now + 400);
        check.accept("lifeline: one donor per recipient (second: " + second + ")", first == null && second != null);
        // cancelled healing costs nothing; a healing multiplier never yields more than is paid
        cancelHeal = true;
        for (t = 401; t <= 425; t++) Lifeline.tick(Collections.singletonList(c), now + t);
        cancelHeal = false;
        boolean freeCancel = near(c.getHealth(), 20f) && near(b.getHealth(), 15f);
        Lifeline.stop(c, now + 426, "test");
        check.accept("lifeline: healing cancelled by another mechanic is not paid for, and a cancel before any transfer costs no cooldown",
                freeCancel && Lifeline.cooldown(c, now + 426) == 0);
        doubleHeal = true;
        Lifeline.start(c, b.getId(), now + 500);
        for (t = 501; t <= 521; t++) Lifeline.tick(Collections.singletonList(c), now + t);
        doubleHeal = false;
        check.accept("lifeline: a healing multiplier never creates health (C " + c.getHealth() + ", B " + b.getHealth() + ")",
                near(c.getHealth(), 19f) && near(b.getHealth(), 16f));
        Lifeline.onDonorHurt(c, now + 522);
        check.accept("lifeline: real damage to the donor cancels the channel", !Lifeline.channeling(c));
        b.setHealth(19.5f);
        Lifeline.clear();
        Lifeline.TEST_PLAYERS.put(a.getId(), a); Lifeline.TEST_PLAYERS.put(b.getId(), b); Lifeline.TEST_PLAYERS.put(c.getId(), c);
        Lifeline.TEST_PLAYERS.put(d.getId(), d); Lifeline.TEST_PLAYERS.put(e.getId(), e); Lifeline.TEST_PLAYERS.put(f.getId(), f);
        c.setHealth(20f);
        Lifeline.start(c, b.getId(), now + 600);
        for (t = 601; t <= 660; t++) Lifeline.tick(Collections.singletonList(c), now + t);
        check.accept("lifeline: stops at the recipient's maximum, paying only the half point gained (C " + c.getHealth() + ")",
                near(c.getHealth(), 19.5f) && near(b.getHealth(), 20f));
        look(c, new Vector3d(c.getX() + 5, c.getEyeY(), c.getZ())); // away from B (B is on the -x side)
        b.setHealth(10f);
        Lifeline.clear();
        Lifeline.TEST_PLAYERS.put(a.getId(), a); Lifeline.TEST_PLAYERS.put(b.getId(), b); Lifeline.TEST_PLAYERS.put(c.getId(), c);
        Lifeline.TEST_PLAYERS.put(d.getId(), d); Lifeline.TEST_PLAYERS.put(e.getId(), e); Lifeline.TEST_PLAYERS.put(f.getId(), f);
        check.accept("lifeline: refused when not aiming at the teammate", Lifeline.start(c, b.getId(), now + 700) != null);
        check.accept("lifeline: refused on an enemy", Lifeline.start(a, e.getId(), now + 700) != null);

        // ---- Guard Thread -----------------------------------------------------------------------------------------
        a.setHealth(20f); b.setHealth(20f); c.setHealth(20f); d.setHealth(20f);
        a.moveTo(x + 0.5, y, z + 0.5, 0, 0);
        b.moveTo(x + 3.5, y, z + 0.5, 0, 0);
        c.moveTo(x + 6.5, y, z + 0.5, 0, 0);
        d.moveTo(x + 3.5, y, z + 3.5, 0, 0);
        long g0 = ThreadMoon.now() - 1000; // real time is past g0 + 60, so the death hook sees an established guard
        String sel = GuardThread.toggle(a, b.getId(), g0);
        check.accept("guard: A chooses to guard B (" + sel + ")", sel == null && GuardThread.guarding(a, b));
        check.accept("guard: a guarded player can't guard (no chains)", GuardThread.toggle(b, c.getId(), g0) != null);
        check.accept("guard: a guardian can't be guarded (no chains)", GuardThread.toggle(c, a.getId(), g0) != null);
        check.accept("guard: one guardian per target", GuardThread.toggle(d, b.getId(), g0) != null);
        check.accept("guard: no cycles (B guarding A while A guards B)", GuardThread.toggle(b, a.getId(), g0) != null);
        check.accept("guard: enemies can't be guarded", GuardThread.toggle(d, e.getId(), g0) != null);
        DamageSource hit = DamageSource.playerAttack(e);
        float kept = GuardThread.split(b, 8.5f, hit, g0);
        check.accept("guard: 8.5 -> target 6.8, guardian pays 1.7, total unchanged (A " + a.getHealth() + ", kept " + kept + ")",
                near(kept, 6.8f) && near(a.getHealth(), 18.3f) && near(GuardThread.lastPaid + kept, 8.5f));
        a.setHealth(20f);
        kept = GuardThread.split(b, 15f, hit, g0);
        check.accept("guard: at most 2 per hit (" + kept + ")", near(kept, 13f) && near(a.getHealth(), 18f));
        a.setHealth(7f);
        kept = GuardThread.split(b, 10f, hit, g0);
        check.accept("guard: the guardian never goes below 6; the rest stays with the target (" + kept + ", A " + a.getHealth() + ")",
                near(kept, 9f) && near(a.getHealth(), 6f));
        a.setHealth(20f);
        kept = GuardThread.split(b, 6f, DamageSource.FALL, g0);
        check.accept("guard: environmental damage (fall) is never shared", near(kept, 6f) && near(a.getHealth(), 20f));
        kept = GuardThread.split(b, 6f, DamageSource.GENERIC, g0);
        check.accept("guard: damage without an attacker is never shared", near(kept, 6f));
        LivingDamageEvent ev = new LivingDamageEvent(b, hit, 5f);
        ThreadMoon.onDamage(ev);
        check.accept("guard: the damage hook applies the split once (5 -> " + ev.getAmount() + ", A " + a.getHealth() + ")",
                near(ev.getAmount(), 4f) && near(a.getHealth(), 19f));
        a.setHealth(20f);
        a.moveTo(x + 20.5, y, z + 0.5, 0, 0);
        GuardThread.tick(Arrays.asList(a), g0 + 4);
        kept = GuardThread.split(b, 10f, hit, g0 + 4);
        check.accept("guard: out of range the selection does nothing (no free protection)", near(kept, 10f) && near(a.getHealth(), 20f));
        a.moveTo(x + 0.5, y, z + 0.5, 0, 0);
        GuardThread.tick(Arrays.asList(a), g0 + 8);

        // ---- Last Stitch ------------------------------------------------------------------------------------------
        ThreadEvent tev = ThreadEvent.get(w);
        tev.resetSpent();
        b.setHealth(0f); // the lethal hit already happened
        boolean early = GuardThread.lastStitch(b, hit, g0 + 50);
        check.accept("last stitch: needs 3 s of established guard", !early && near(a.getHealth(), 20f));
        b.setInvisible(false);
        b.setItemInHand(Hand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        boolean withTotem = GuardThread.lastStitch(b, hit, g0 + 70);
        b.setItemInHand(Hand.OFF_HAND, ItemStack.EMPTY);
        check.accept("last stitch: a Totem of Undying is left to do its job (never both)", !withTotem);
        check.accept("last stitch: never for environmental or attacker-less deaths",
                !GuardThread.lastStitch(b, DamageSource.OUT_OF_WORLD, g0 + 70) && !GuardThread.lastStitch(b, DamageSource.STARVE, g0 + 70));
        LivingDeathEvent death = new LivingDeathEvent(b, hit);
        ThreadMoon.onDeath(death);
        check.accept("last stitch: the lethal hit leaves the target at half a heart and the guardian pays exactly 4 (" + GuardThread.lastStitchWhy + ", B " + b.getHealth()
                + ", A " + a.getHealth() + ")", death.isCanceled() && near(b.getHealth(), 1f) && near(a.getHealth(), 16f));
        check.accept("last stitch: both are spent for this event, the guard snapped, both on guard cooldown",
                tev.spent(a.getUUID()) && tev.spent(b.getUUID()) && !GuardThread.guarding(a, b) && GuardThread.toggle(a, b.getId(), g0 + 80) != null);
        // swapping guardians can't buy a second save (cooldowns lifted to try)
        GuardThread.clearAll();
        String dsel = GuardThread.toggle(d, b.getId(), g0 + 100);
        GuardThread.tick(Arrays.asList(d), g0 + 100);
        b.setHealth(0f);
        float dBefore = d.getHealth();
        boolean again = GuardThread.lastStitch(b, hit, g0 + 500);
        check.accept("last stitch: one participation per player per event, even with a new guardian (" + dsel + ")",
                dsel == null && !again && near(d.getHealth(), dBefore));
        b.setHealth(20f);
        CompoundNBT saved = tev.save(new CompoundNBT());
        ThreadEvent copy = new ThreadEvent();
        copy.load(saved);
        check.accept("last stitch: usage survives a save/restart of the same event", copy.spent(a.getUUID()) && copy.spent(b.getUUID())
                && copy.id().equals(tev.id()) && copy.active());
        copy.update(w, false);
        copy.update(w, true);
        check.accept("last stitch: a genuinely new Thread Moon event resets eligibility", !copy.spent(a.getUUID()) && !copy.id().equals(tev.id()));
        tev.resetSpent();
        GuardThread.clearAll();

        // ---- Knotbind (direct hits) -------------------------------------------------------------------------------
        Knotbind.resetTimers();
        e.moveTo(x + 4.5, y, z + 6.5, 0, 0);
        f.moveTo(x + 14.5, y, z + 6.5, 0, 0);
        long k0 = Knotbind_now();
        Knotbind.hit(a, b, k0);
        check.accept("knotbind: teammates can't be targeted (" + Knotbind.LAST_MSG.get(a.getUUID()) + ")", !Knotbind.inKnot(b)
                && Knotbind.cooldown(a, k0) == 0);
        Knotbind.hit(a, e, k0);
        check.accept("knotbind: the first valid hit marks and starts the 25 s cooldown", Knotbind.cooldown(a, k0) >= 499);
        Knotbind.hit(a, f, k0 + 5);
        check.accept("knotbind: a second target more than 6 blocks from the first is refused, the mark stays ("
                + Knotbind.LAST_MSG.get(a.getUUID()) + ")", !Knotbind.inKnot(e) && Knotbind.LAST_MSG.get(a.getUUID()).contains("far"));
        f.moveTo(x + 8.5, y, z + 6.5, 0, 0);
        Knotbind.hit(a, f, k0 + 10);
        Knotbind.Knot knot = Knotbind.knotOf(e);
        check.accept("knotbind: two enemies within 6 blocks are knotted", knot != null && knot.has2(e, f) && Knotbind.ownsKnot(a));
        check.accept("knotbind: one knot per target (no stacking)", Knotbind.invalid(c, e, k0 + 11) != null);
        // tether: over-stretch nudges both towards each other, bounded, never a teleport
        e.setDeltaMovement(Vector3d.ZERO);
        f.setDeltaMovement(Vector3d.ZERO);
        f.moveTo(x + 12.0, y, z + 6.5, 0, 0); // 7.5 apart (> 6)
        Vector3d pe = new Vector3d(e.getX(), e.getY(), e.getZ()), pf = new Vector3d(f.getX(), f.getY(), f.getZ());
        Knotbind.tick(w, k0 + 11);
        Vector3d ve = e.getDeltaMovement(), vf = f.getDeltaMovement();
        check.accept("knotbind: beyond the tether both get a small pull towards each other (e " + ve + ", f " + vf + ")",
                ve.x > 0.01 && vf.x < -0.01 && ve.length() <= 0.1201 && vf.length() <= 0.1201 && ve.y == 0 && vf.y == 0
                        && new Vector3d(e.getX(), e.getY(), e.getZ()).equals(pe) && new Vector3d(f.getX(), f.getY(), f.getZ()).equals(pf));
        // unravel: progress reset by voluntary action, success frees both with immunity
        Knotbind.unravel(e, true, k0 + 12);
        Knotbind.resetUnravel(e, k0 + 30);
        Knotbind.tick(w, k0 + 43);
        boolean heldOn = Knotbind.inKnot(e);
        Knotbind.tick(w, k0 + 60);
        check.accept("knotbind: moving/attacking restarts unravel; 1.5 s of standing still unravels both (" + Knotbind.lastRelease + ")",
                heldOn && !Knotbind.inKnot(e) && !Knotbind.inKnot(f) && "unravelled".equals(Knotbind.lastRelease));
        check.accept("knotbind: both get 8 s of immunity from any caster", Knotbind.immune(e, k0 + 61) && Knotbind.immune(f, k0 + 61)
                && Knotbind.invalid(c == null ? a : a, e, k0 + 61) != null);
        // wall breaks it; teleport breaks it; expiry
        Knotbind.resetTimers();
        f.moveTo(x + 8.5, y, z + 6.5, 0, 0);
        Knotbind.hit(a, e, k0 + 100);
        Knotbind.hit(a, f, k0 + 101);
        for (int dy = -1; dy <= 3; dy++) for (int dz = -3; dz <= 3; dz++) w.setBlock(new BlockPos(x + 7, y + dy, z + 6 + dz), Blocks.STONE.defaultBlockState(), 2);
        Knotbind.tick(w, k0 + 102);
        boolean wallBroke = !Knotbind.inKnot(e) && "obstructed".equals(Knotbind.lastRelease);
        for (int dy = -1; dy <= 3; dy++) for (int dz = -3; dz <= 3; dz++) w.setBlock(new BlockPos(x + 7, y + dy, z + 6 + dz), Blocks.AIR.defaultBlockState(), 2);
        check.accept("knotbind: a solid block between them breaks the knot", wallBroke);
        Knotbind.resetTimers();
        f.moveTo(x + 8.5, y, z + 6.5, 0, 0);
        Knotbind.hit(a, e, k0 + 200);
        Knotbind.hit(a, f, k0 + 201);
        f.moveTo(x + 8.5, y, z + 30.5, 0, 0);
        Knotbind.tick(w, k0 + 202);
        check.accept("knotbind: a teleport breaks it (no chasing) (" + Knotbind.lastRelease + ")", !Knotbind.inKnot(e) && "teleported".equals(Knotbind.lastRelease));
        Knotbind.resetTimers();
        f.moveTo(x + 8.5, y, z + 6.5, 0, 0);
        Knotbind.hit(a, e, k0 + 300);
        Knotbind.hit(a, f, k0 + 301);
        Knotbind.tick(w, k0 + 400);
        boolean live = Knotbind.inKnot(e);
        Knotbind.tick(w, k0 + 401);
        check.accept("knotbind: lasts 5 s from the pair, then releases", live && !Knotbind.inKnot(e) && "expired".equals(Knotbind.lastRelease));
        Knotbind.resetTimers();
        // reactions scheduler
        ThreadReactions.clear();
        long r0 = 1_000_000L;
        ThreadReactions.request(w, ThreadReactions.LIFELINE, r0);
        ThreadReactions.request(w, ThreadReactions.GUARD, r0 + 20);
        byte pend = ThreadReactions.pending(w);
        ThreadReactions.tick(w, r0 + 61);
        check.accept("reactions: one ordinary reaction per 8 s, a waiting one is dropped after 2 s (no stale replay)",
                count(ThreadReactions.LIFELINE) == 1 && pend == ThreadReactions.GUARD && ThreadReactions.pending(w) == 0 && count(ThreadReactions.GUARD) == 0);
        ThreadReactions.request(w, ThreadReactions.KNOT, r0 + 170);
        ThreadReactions.request(w, ThreadReactions.LAST_STITCH, r0 + 175);
        long[] last = ThreadReactions.last(w);
        ThreadReactions.request(w, ThreadReactions.LAST_STITCH, r0 + 180);
        check.accept("reactions: Last Stitch outranks and takes over within 5 ticks; rescues within 6 s merge into one",
                count(ThreadReactions.KNOT) == 1 && count(ThreadReactions.LAST_STITCH) == 1 && last[0] == ThreadReactions.LAST_STITCH && last[2] <= r0 + 180);
        ThreadReactions.request(w, ThreadReactions.LIFELINE, r0 + 300);
        ThreadReactions.request(w, ThreadReactions.KNOT, r0 + 301);
        ThreadReactions.request(w, ThreadReactions.KNOT, r0 + 302);
        check.accept("reactions: the pending slot keeps the higher priority and coalesces duplicates", ThreadReactions.pending(w) == ThreadReactions.KNOT);
        ThreadReactions.clear();

        // ---- privacy: an enemy's state never contains our team's links or guards ---------------------------------
        GuardThread.toggle(a, b.getId(), g0 + 2000);
        ThreadPacket enemy = ThreadNet.build(e, now, true), mate = ThreadNet.build(c, now, true);
        boolean leak = false;
        for (int id : enemy.links) leak |= id == a.getId() || id == b.getId();
        for (int id : enemy.guards) leak |= id == a.getId() || id == b.getId();
        check.accept("thread: an enemy's packet carries none of our links or guard pairs", !leak && enemy.guardTarget.isEmpty());
        check.accept("thread: a guardian's own packet shows the selected target", ThreadNet.build(a, now, true).guardTarget.equals(b.getScoreboardName()));
        GuardThread.clearAll();

        // cleanup of the test teams
        for (ServerPlayerEntity p : Arrays.asList(a, b, c, d, e, f)) teams.remove(p.getUUID());
        teams.delete("ecm_red");
        teams.delete("ecm_blue");
        EcMoons.LOGGER.info("[ECMOONS-SELFTEST] thread checks done; mate packet guards " + mate.guards.length);
    }

    private static long Knotbind_now() {
        return ThreadMoon.now();
    }

    private static int count(byte t) {
        return ThreadReactions.SENT.getOrDefault(t, 0);
    }

    // ---- Knotbind with the real projectile on real mobs ----------------------------------------------------------

    private static final List<MobEntity> MOBS = new ArrayList<>();
    private static ServerPlayerEntity thrower;
    private static int fx, fz;

    static void yarnSetup(ServerWorld w, BiConsumer<String, Boolean> check) {
        BlockPos sp = w.getSharedSpawnPos();
        int x = sp.getX() - 30, z = sp.getZ() - 30;
        w.getChunk(x >> 4, z >> 4);
        w.setChunkForced(x >> 4, z >> 4, true);
        fx = x >> 4;
        fz = z >> 4;
        int y = w.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (MobEntity old : w.getEntitiesOfClass(MobEntity.class, new AxisAlignedBB(x - 30, y - 20, z - 30, x + 30, y + 20, z + 30))) old.remove();
        for (int dx = -12; dx <= 12; dx++) for (int dz = -6; dz <= 6; dz++) {
            w.setBlock(new BlockPos(x + dx, y - 1, z + dz), Blocks.STONE.defaultBlockState(), 2);
            for (int dy = 0; dy <= 3; dy++) w.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.defaultBlockState(), 2);
            w.setBlock(new BlockPos(x + dx, y + 4, z + dz), Blocks.BLACK_STAINED_GLASS.defaultBlockState(), 2);
        }
        MOBS.clear();
        for (int i = 0; i < 2; i++) {
            MobEntity m = EntityType.ZOMBIE.create(w);
            m.moveTo(x + 8.5, y, z + (i == 0 ? -1.5 : 2.5), 0, 0);
            m.setPersistenceRequired();
            m.setNoAi(true);
            w.addFreshEntity(m);
            MOBS.add(m);
        }
        thrower = fake(w, "ecm_t_thrower");
        MoonTeams.get(w.getServer()).create("ecm_green", "Green Test");
        MoonTeams.get(w.getServer()).assign(thrower.getUUID(), "thrower", "ecm_green");
        thrower.moveTo(x + 0.5, y, z + 0.5, 0, 0);
        Knotbind.resetTimers();
        look(thrower, MOBS.get(0).position().add(0, 1, 0));
        String r = Knotbind.fire(thrower);
        check.accept("knotbind: a yarn is thrown (" + r + ")", r == null);
    }

    static void yarnSecond(ServerWorld w, BiConsumer<String, Boolean> check) {
        check.accept("knotbind: the yarn's hit marked the first zombie", Knotbind.knotOf(MOBS.get(0)) == null
                && ThreadNet.build(thrower, ThreadMoon.now(), true).markTarget == MOBS.get(0).getId());
        look(thrower, MOBS.get(1).position().add(0, 1, 0));
        String r = Knotbind.fire(thrower);
        check.accept("knotbind: second throw (" + r + ")", r == null);
    }

    static void yarnDone(ServerWorld w, BiConsumer<String, Boolean> check) {
        Knotbind.Knot k = Knotbind.knotOf(MOBS.get(0));
        check.accept("knotbind: two real yarn hits knotted the two zombies", k != null && k.has2(MOBS.get(0), MOBS.get(1)));
        Knotbind.endAll(w);
        check.accept("knotbind: the moon ending removes knots, marks and flying yarn", Knotbind.knots().isEmpty());
        for (MobEntity m : MOBS) m.remove();
        MOBS.clear();
        MoonTeams.get(w.getServer()).remove(thrower.getUUID());
        MoonTeams.get(w.getServer()).delete("ecm_green");
        w.setChunkForced(fx, fz, false);
        Lifeline.TEST_PLAYERS.clear();
    }
}
