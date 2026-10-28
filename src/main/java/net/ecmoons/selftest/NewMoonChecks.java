package net.ecmoons.selftest;

import com.mojang.authlib.GameProfile;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.Moons;
import net.ecmoons.config.Settings;
import net.ecmoons.event.DefaultEvents;
import net.ecmoons.feature.GluttonyFeatures;
import net.ecmoons.feature.MoonTeams;
import net.ecmoons.feature.RevealingFeatures;
import net.ecmoons.feature.ThreadFeatures;
import net.ecmoons.feature.war.WarArena;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Dedicated-server checks for the Revealing, Thread, War and Gluttony moons (no connected player needed). */
final class NewMoonChecks {
    private NewMoonChecks() {}

    static void staticChecks(ServerWorld w, BiConsumer<String, Boolean> check) {
        MinecraftServer server = w.getServer();
        // Feast -> Gluttony migration
        try {
            Path dir = Files.createTempDirectory("ecmoons-migrate");
            Files.write(dir.resolve("ecmoons-feast_moon.json"), "{\"type\":\"ecmoons:feast_moon\",\"chance\":0.07}".getBytes(StandardCharsets.UTF_8));
            DefaultEvents.migrateFeastMoon(dir);
            String out = new String(Files.readAllBytes(dir.resolve("ecmoons-gluttony_moon.json")), StandardCharsets.UTF_8);
            check.accept("feast_moon file migrated to gluttony_moon (chance kept, type renamed)",
                    out.contains("ecmoons:gluttony_moon") && out.contains("0.07") && !Files.exists(dir.resolve("ecmoons-feast_moon.json"))
                            && Files.exists(dir.resolve("ecmoons-feast_moon.json.migrated")));
        } catch (Exception e) {
            check.accept("feast migration (" + e + ")", false);
        }

        // Moon teams: UUID based, one team per player, persisted as NBT
        MoonTeams teams = new MoonTeams();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        teams.create("red", "Red Kingdom");
        teams.create("blue", "Blue Kingdom");
        teams.assign(a, "A", "red");
        teams.assign(b, "B", "red");
        teams.assign(c, "C", "red");
        teams.assign(c, "C", "blue");
        check.accept("moon teams: same team", teams.sameTeam(a, b));
        check.accept("moon teams: reassigning moves the player (one team each)", !teams.sameTeam(a, c) && "blue".equals(teams.teamOf(c)));
        check.accept("moon teams: assigning to a missing team fails", !teams.assign(a, "A", "nope"));
        MoonTeams copy = new MoonTeams();
        copy.load(teams.save(new CompoundNBT()));
        check.accept("moon teams: NBT round trip", copy.sameTeam(a, b) && "Red Kingdom".equals(copy.displayName(copy.teamOf(a)))
                && copy.membersOf("red").size() == 2);
        teams.delete("red");
        check.accept("moon teams: deleting a team removes its members", teams.teamOf(a) == null);

        // Thread: bond counting, line of sight, cap, excluded damage
        BlockPos spawn = w.getSharedSpawnPos(); // spawn chunks are loaded
        int bx = spawn.getX(), bz = spawn.getZ();
        int y = w.getHeight(Heightmap.Type.MOTION_BLOCKING, bx, bz) + 40;
        FakePlayer p1 = FakePlayerFactory.get(w, new GameProfile(UUID.randomUUID(), "ecm_thread1"));
        FakePlayer p2 = FakePlayerFactory.get(w, new GameProfile(UUID.randomUUID(), "ecm_thread2"));
        FakePlayer p3 = FakePlayerFactory.get(w, new GameProfile(UUID.randomUUID(), "ecm_thread3"));
        p1.moveTo(bx + 0.5, y, bz + 0.5, 0, 0);
        p2.moveTo(bx + 6.5, y, bz + 0.5, 0, 0);
        p3.moveTo(bx + 30.5, y, bz + 0.5, 0, 0);
        Map<UUID, Integer> counts = new HashMap<>();
        List<Integer> links = new ArrayList<>();
        ThreadFeatures.bond(w, Arrays.asList(p1, p2, p3), 12, true, counts, links);
        EcMoons.LOGGER.info("[ECMOONS-SELFTEST] thread positions " + p1.position() + " " + p2.position() + " " + p3.position()
                + " d12=" + p1.distanceTo(p2) + " los=" + ThreadFeatures.lineOfSightForTest(w, p1, p2));
        check.accept("thread: two teammates 6 blocks apart bond, one 24+ away does not (" + counts + ")",
                counts.getOrDefault(p1.getUUID(), 0) == 1 && counts.getOrDefault(p2.getUUID(), 0) == 1 && !counts.containsKey(p3.getUUID())
                        && links.size() == 2);
        for (int dy = -1; dy <= 3; dy++) for (int dz = -2; dz <= 2; dz++) w.setBlock(new BlockPos(bx + 3, y + dy, bz + dz), Blocks.STONE.defaultBlockState(), 2);
        counts.clear();
        links.clear();
        ThreadFeatures.bond(w, Arrays.asList(p1, p2), 12, true, counts, links);
        check.accept("thread: a wall between teammates breaks the bond", counts.isEmpty());
        counts.clear();
        ThreadFeatures.bond(w, Arrays.asList(p1, p2), 12, false, counts, links);
        check.accept("thread: without requireLineOfSight the wall does not matter", counts.size() == 2);
        for (int dy = -1; dy <= 3; dy++) for (int dz = -2; dz <= 2; dz++) w.setBlock(new BlockPos(bx + 3, y + dy, bz + dz), Blocks.AIR.defaultBlockState(), 2);
        check.accept("thread: 1 teammate = 5%, 3 = 15%, 5 = capped 15%", Math.abs(ThreadFeatures.target(1) - 0.05f) < 1e-6
                && Math.abs(ThreadFeatures.target(3) - 0.15f) < 1e-6 && Math.abs(ThreadFeatures.target(5) - 0.15f) < 1e-6);
        check.accept("thread: 10 damage with 15% bond = 8.5", Math.abs(ThreadFeatures.reduce(10f, 0.15f, DamageSource.GENERIC) - 8.5f) < 1e-5);
        check.accept("thread: starvation/drowning/void are never reduced", ThreadFeatures.reduce(10f, 0.15f, DamageSource.STARVE) == 10f
                && ThreadFeatures.reduce(10f, 0.15f, DamageSource.DROWN) == 10f && ThreadFeatures.reduce(10f, 0.15f, DamageSource.OUT_OF_WORLD) == 10f);

        // Revealing: underground players are marked at the surface; creative/exempt hidden
        int mx = bx + 9, mz = bz + 9;
        w.getChunk(mx >> 4, mz >> 4); // make sure the heightmap is loaded
        int surface = w.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, mx, mz);
        FakePlayer mole = FakePlayerFactory.get(w, new GameProfile(UUID.randomUUID(), "ecm_mole"));
        mole.moveTo(mx + 0.5, Math.max(5, surface - 20), mz + 0.5, 0, 0);
        Vector3d m = RevealingFeatures.markerPos(mole);
        check.accept("revealing: underground player marked at the surface (y " + m.y + " vs surface " + surface + "), same x/z",
                m.y == surface && m.x == mole.getX() && m.z == mole.getZ());
        mole.moveTo(mx + 0.5, surface + 10, mz + 0.5, 0, 0);
        check.accept("revealing: a player above the surface is marked where they are", RevealingFeatures.markerPos(mole).y == surface + 10);
        check.accept("revealing: survival player is revealable", !RevealingFeatures.hidden(mole));
        mole.addTag(RevealingFeatures.EXEMPT_TAG);
        check.accept("revealing: exempt tag hides the player", RevealingFeatures.hidden(mole));
        mole.removeTag(RevealingFeatures.EXEMPT_TAG);
        mole.gameMode.setGameModeForPlayer(net.minecraft.world.GameType.SPECTATOR);
        check.accept("revealing: spectators are never revealed", RevealingFeatures.hidden(mole));
        mole.gameMode.setGameModeForPlayer(net.minecraft.world.GameType.SURVIVAL);

        // Gluttony categories
        check.accept("gluttony: beef and cooked beef share a category", "beef".equals(GluttonyFeatures.category(Items.BEEF))
                && "beef".equals(GluttonyFeatures.category(Items.COOKED_BEEF)));
        check.accept("gluttony: rotten flesh never counts", GluttonyFeatures.category(Items.ROTTEN_FLESH) == null);
        check.accept("gluttony: unlisted food is its own category", "minecraft:stick".equals(GluttonyFeatures.category(Items.STICK)));

        // War arena dimension and builder
        ServerWorld arena = WarArena.world(server);
        check.accept("war: arena dimension ecmoons:war_arena loaded from the data pack", arena != null);
        if (arena != null) {
            check.accept("war: arena dimension has no Enhanced Celestials lunar context (no moons inside)", Moons.context(arena) == null);
            WarArena.Layout l = WarArena.build(arena);
            check.accept("war: default arena has 16 cells (" + l.cells.size() + ")", l.cells.size() == 16);
            boolean cellsOk = true;
            for (BlockPos cell : l.cells) {
                cellsOk &= arena.getBlockState(cell).isAir() && arena.getBlockState(cell.above()).isAir()
                        && !arena.getBlockState(cell.below()).isAir();
            }
            check.accept("war: every cell has a floor and 2 free blocks", cellsOk);
            boolean distinct = l.cells.stream().distinct().count() == l.cells.size();
            check.accept("war: cells are distinct", distinct);
            check.accept("war: gates are iron bars", !l.gates.isEmpty() && arena.getBlockState(l.gates.get(0)).is(Blocks.IRON_BARS));
            check.accept("war: viewing gallery has a floor", !arena.getBlockState(l.spectator.below()).isAir()
                    && arena.getBlockState(l.spectator).isAir());
            WarArena.openGates(arena, l);
            check.accept("war: gates open", arena.getBlockState(l.gates.get(0)).isAir());
            WarArena.clear(arena);
            check.accept("war: arena removed afterwards", arena.getBlockState(l.cells.get(0).below()).isAir()
                    && arena.getBlockState(new BlockPos(0, WarArena.FLOOR_Y, 0)).isAir());
        }
        EcMoons.LOGGER.info("[ECMOONS-SELFTEST] new-moon static checks done; settings war.kit=" + Settings.server().list("war.kit"));
    }

    /** Gluttony with a fake player while the Gluttony Moon is up (moonChecks loop). */
    static void gluttony(ServerWorld w, MoonType type, BiConsumer<String, Boolean> check) {
        FakePlayer p = FakePlayerFactory.get(w, new GameProfile(UUID.randomUUID(), "ecm_glutton"));
        p.setHealth(20f);
        GluttonyFeatures.reset(p);
        boolean active = Moons.is(w, MoonType.GLUTTONY);
        if (active) {
            GluttonyFeatures.onAte(p, new ItemStack(Items.BEEF));
            GluttonyFeatures.onAte(p, new ItemStack(Items.COOKED_BEEF));
            check.accept("gluttony: beef then cooked beef = 1 heart (max " + p.getMaxHealth() + ")", p.getMaxHealth() == 22f);
            check.accept("gluttony: no instant heal", p.getHealth() == 20f);
            GluttonyFeatures.onAte(p, new ItemStack(Items.BREAD));
            GluttonyFeatures.onAte(p, new ItemStack(Items.ROTTEN_FLESH));
            GluttonyFeatures.onAte(p, new ItemStack(Items.APPLE));
            GluttonyFeatures.onAte(p, new ItemStack(Items.CARROT));
            GluttonyFeatures.onAte(p, new ItemStack(Items.COOKIE));
            check.accept("gluttony: capped at 4 bonus hearts (max " + p.getMaxHealth() + ")", p.getMaxHealth() == 28f
                    && GluttonyFeatures.hearts(p) == 4);
            p.setHealth(28f);
            GluttonyFeatures.onDeath(p);
            check.accept("gluttony: death resets progress and clamps health", p.getMaxHealth() == 20f && p.getHealth() == 20f
                    && GluttonyFeatures.eaten(p).isEmpty());
            GluttonyFeatures.onAte(p, new ItemStack(Items.BREAD));
            GluttonyFeatures.onAte(p, new ItemStack(Items.APPLE));
            check.accept("gluttony: progress builds again after reset", p.getMaxHealth() == 24f);
            p.setHealth(24f);
        } else {
            GluttonyFeatures.tick(p);
            check.accept("gluttony: no bonus hearts during " + type.id(), p.getMaxHealth() == 20f);
        }
    }

    /** After the Gluttony Moon ended: the fake player from the gluttony step loses the hearts on the next tick. */
    static void gluttonyEnded(ServerWorld w, BiConsumer<String, Boolean> check) {
        FakePlayer p = FakePlayerFactory.get(w, new GameProfile(UUID.randomUUID(), "ecm_glutton2"));
        p.setHealth(20f);
        GluttonyFeatures.reset(p);
        GluttonyFeatures.apply(p, 3);
        p.setHealth(26f);
        GluttonyFeatures.tick(p); // moon not active: removed and clamped
        check.accept("gluttony: bonus removed and health clamped when the moon is not up (" + p.getMaxHealth() + "/" + p.getHealth() + ")",
                p.getMaxHealth() == 20f && p.getHealth() == 20f);
    }
}
