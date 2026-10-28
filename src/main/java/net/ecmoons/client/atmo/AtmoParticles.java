package net.ecmoons.client.atmo;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.ecmoons.EcMoons;
import net.ecmoons.MoonType;
import net.ecmoons.config.ClientConfig;
import net.ecmoons.config.Settings;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BushBlock;
import net.minecraft.block.CampfireBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.world.LightType;
import net.minecraftforge.common.Tags;

import java.util.Random;

/**
 * The atmosphere's own lightweight particles: a fixed pool (no entities, no vanilla particle objects, no per-frame
 * allocation) with one shared budget per player (atmosphere.maxParticlesLow/Medium/High). Every effect spawns
 * into the same pool, so effects can never add up beyond the cap; when the pool is full, ambient spawns are
 * skipped. Particles are depth-tested, so they never show through solid blocks.
 * <p>
 * Block-dependent effects (Harvest pollen, Prospector ore glints, Arcane sparkles, Gluttony steam) use a few random
 * samples per tick around the player instead of area scans. Ore glints only appear on ore faces that are exposed
 * to air AND in the player's direct line of sight, so they can never reveal hidden ores.
 */
public final class AtmoParticles {
    public static final ResourceLocation SPRITES = new ResourceLocation(EcMoons.MOD_ID, "textures/atmosphere/sprites.png");
    static final int MAX = 64;
    // sprites in the sheet
    static final int GLOW = 0, CRYSTAL = 1, EMBER = 2, RUNE_A = 3, RUNE_B = 4, SPARK = 5, WISP = 6, SILK = 7;
    // behaviours
    static final int B_FLOAT = 0, B_FALL = 1, B_RISE = 2, B_STATIC = 3, B_BURST = 4, B_STEAM = 5, B_WISP = 6, B_BREATH = 7;

    private static final double[] x = new double[MAX], y = new double[MAX], z = new double[MAX];
    private static final float[] vx = new float[MAX], vy = new float[MAX], vz = new float[MAX];
    private static final float[] size = new float[MAX], grow = new float[MAX], alpha = new float[MAX], light = new float[MAX];
    private static final float[] r = new float[MAX], g = new float[MAX], b = new float[MAX];
    private static final int[] age = new int[MAX], life = new int[MAX], sprite = new int[MAX], behaviour = new int[MAX];
    private static final boolean[] additive = new boolean[MAX], priority = new boolean[MAX];
    private static int count;
    private static final float[] spawnDebt = new float[MoonType.values().length];
    private static final Random RANDOM = new Random();
    private static long nextBreath;
    // bounded caches of nearby block features
    private static final long[] glintPos = new long[24];
    private static final int[] glintFace = new int[24];
    private static final long[] glintExpire = new long[24];
    private static final long[] steamPos = new long[12];
    private static final long[] steamExpire = new long[12];
    public static int lastRendered;
    private static float windAngle;

    private AtmoParticles() {}

    public static int count() {
        return count;
    }

    /** Particles inside a box (tests: nothing spawns inside a closed room). */
    public static int countInside(double x0, double y0, double z0, double x1, double y1, double z1) {
        int n = 0;
        for (int i = 0; i < count; i++) if (x[i] >= x0 && x[i] <= x1 && y[i] >= y0 && y[i] <= y1 && z[i] >= z0 && z[i] <= z1) n++;
        return n;
    }

    public static void clear() {
        count = 0;
        for (int i = 0; i < glintExpire.length; i++) glintExpire[i] = 0;
        for (int i = 0; i < steamExpire.length; i++) steamExpire[i] = 0;
    }

    /** Current cap for this player's quality setting. */
    public static int cap() {
        Settings s = Settings.client();
        switch (Atmosphere.quality()) {
            case LOW: return Math.min(MAX, s.i("atmosphere.maxParticlesLow"));
            case MEDIUM: return Math.min(MAX, s.i("atmosphere.maxParticlesMedium"));
            case HIGH: return Math.min(MAX, s.i("atmosphere.maxParticlesHigh"));
            default: return 0;
        }
    }

    private static int add(double px, double py, double pz, int spr, int beh, int lifeTicks, float sz, float[] col, float a, boolean glow, boolean prio) {
        int idx;
        if (count < MAX && count < Math.max(cap(), prio ? cap() : 0)) {
            idx = count++;
        } else if (prio && count > 0) {
            idx = 0; // bursts replace the oldest ambient particle rather than exceeding the cap
            for (int i = 0; i < count; i++) if (!priority[i] && age[i] > age[idx]) idx = i;
        } else {
            return -1;
        }
        x[idx] = px; y[idx] = py; z[idx] = pz;
        vx[idx] = vy[idx] = vz[idx] = 0;
        sprite[idx] = spr; behaviour[idx] = beh; life[idx] = lifeTicks; age[idx] = 0;
        size[idx] = sz; grow[idx] = 0; alpha[idx] = a; additive[idx] = glow; priority[idx] = prio;
        r[idx] = col[0]; g[idx] = col[1]; b[idx] = col[2];
        light[idx] = 1f;
        return idx;
    }

    private static void remove(int i) {
        int last = --count;
        if (i == last) return;
        x[i] = x[last]; y[i] = y[last]; z[i] = z[last];
        vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last];
        size[i] = size[last]; grow[i] = grow[last]; alpha[i] = alpha[last]; light[i] = light[last];
        r[i] = r[last]; g[i] = g[last]; b[i] = b[last];
        age[i] = age[last]; life[i] = life[last]; sprite[i] = sprite[last]; behaviour[i] = behaviour[last];
        additive[i] = additive[last]; priority[i] = priority[last];
    }

    // ---- tick -----------------------------------------------------------------------------------------------------

    static void tick(Minecraft mc, ClientWorld level) {
        Entity p = mc.player;
        double range = Settings.client().d("atmosphere.particleRange");
        windAngle += 0.002f;
        for (int i = count - 1; i >= 0; i--) {
            age[i]++;
            switch (behaviour[i]) {
                case B_FALL:
                    vx[i] = (float) Math.sin((age[i] + i * 13) * 0.05) * 0.012f;
                    break;
                case B_RISE:
                    vx[i] += (RANDOM.nextFloat() - 0.5f) * 0.004f;
                    vz[i] += (RANDOM.nextFloat() - 0.5f) * 0.004f;
                    break;
                case B_FLOAT:
                    vx[i] = vx[i] * 0.98f + (RANDOM.nextFloat() - 0.5f) * 0.002f;
                    vy[i] = vy[i] * 0.98f + (RANDOM.nextFloat() - 0.5f) * 0.002f;
                    vz[i] = vz[i] * 0.98f + (RANDOM.nextFloat() - 0.5f) * 0.002f;
                    break;
                case B_BURST:
                    vx[i] *= 0.88f; vy[i] = vy[i] * 0.88f + 0.002f; vz[i] *= 0.88f;
                    break;
                case B_STEAM:
                case B_BREATH:
                    vx[i] *= 0.96f; vz[i] *= 0.96f;
                    break;
                default:
                    break;
            }
            x[i] += vx[i]; y[i] += vy[i]; z[i] += vz[i];
            size[i] += grow[i];
            boolean dead = age[i] >= life[i];
            if (!dead && Settings.client().b("atmosphere.enableParticles") && (age[i] & 7) == 0) {
                double dx = x[i] - p.getX(), dz = z[i] - p.getZ();
                if (dx * dx + dz * dz > range * range * 1.2) dead = true; // distance culling
                else if (behaviour[i] == B_FALL || behaviour[i] == B_RISE) {
                    BlockPos bp = new BlockPos(x[i], y[i], z[i]);
                    if (level.getBlockState(bp).isSolidRender(level, bp)) dead = true; // hit the ground or a roof
                }
            }
            if (dead || !Atmosphere.on("enableParticles")) remove(i);
        }
        if (!Atmosphere.on("enableParticles")) return;
        int cap = cap();
        if (cap <= 0) return;
        float total = 0f;
        for (MoonType t : MoonType.MOONS) total += Atmosphere.weight(t);
        effectsPerTick(mc, level); // block-feature effects keep sampling even when the pool is full
        if (count >= cap * Math.min(1f, total)) return; // particles are introduced gradually during the fade-in
        float qf = Atmosphere.quality() == ClientConfig.Quality.HIGH ? 1.2f : Atmosphere.quality() == ClientConfig.Quality.LOW ? 0.6f : 1f;
        for (MoonType t : MoonType.MOONS) {
            float w = Atmosphere.weight(t);
            if (w <= 0f) continue;
            AtmoProfile prof = AtmoProfile.of(t);
            if (!prof.particles) continue;
            spawnDebt[t.ordinal()] += rate(t) * prof.density * w * qf;
            int guard = 0;
            while (spawnDebt[t.ordinal()] >= 1f && guard++ < 3) {
                spawnDebt[t.ordinal()] -= 1f;
                spawn(t, prof, mc, level, w);
            }
            if (spawnDebt[t.ordinal()] > 3f) spawnDebt[t.ordinal()] = 3f;
        }
    }

    /** Expected spawns per tick at full strength. */
    private static float rate(MoonType t) {
        switch (t) {
            case BLOOD: return 0.02f;
            case BLUE: return 0.03f;
            case HARVEST: return 0.25f;
            case MIST: return 0.05f;
            case FROST: return 0.45f;
            case ARCANE: return 0.15f;
            case PROSPECTOR: return 0.18f;
            case WISDOM: return 0.06f;
            case QUIET: return 0.008f;
            case DRIFT: return 0.35f;
            case REVEALING: return 0.015f;
            case THREAD: return 0.03f;
            case WAR: return 0.3f * (Atmosphere.inArena() ? 1.6f : 1f) * (1f + AtmoWar.turbulence());
            case GLUTTONY: return 0.07f;
            default: return 0f;
        }
    }

    private static double rnd(double spread) {
        return (RANDOM.nextDouble() - 0.5) * 2 * spread;
    }

    private static boolean openAir(ClientWorld level, double px, double py, double pz, boolean needSky) {
        BlockPos bp = new BlockPos(px, py, pz);
        if (!level.isLoaded(bp)) return false;
        if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) return false;
        return !needSky || level.canSeeSky(bp);
    }

    private static float skyLight(ClientWorld level, double px, double py, double pz) {
        BlockPos bp = new BlockPos(px, py, pz);
        float block = level.getBrightness(LightType.BLOCK, bp) / 15f;
        float sky = level.getBrightness(LightType.SKY, bp) / 15f * (1f - level.getSkyDarken(1f) * 0.9f);
        return 0.25f + 0.75f * Math.max(block, Math.max(sky, 0.35f));
    }

    private static void spawn(MoonType t, AtmoProfile prof, Minecraft mc, ClientWorld level, float w) {
        Entity p = mc.player;
        double px = p.getX(), py = p.getY(), pz = p.getZ();
        float[] acc = prof.accent;
        int i;
        switch (t) {
            case FROST: {
                if (!ClientConfig.FROST_PARTICLES.get()) return;
                double sx = px + rnd(12), sy = py + 3 + RANDOM.nextDouble() * 7, sz = pz + rnd(12);
                if (!openAir(level, sx, sy, sz, true)) return;
                i = add(sx, sy, sz, CRYSTAL, B_FALL, 140 + RANDOM.nextInt(80), 0.1f + RANDOM.nextFloat() * 0.08f, acc, 0.85f, true, false);
                if (i >= 0) vy[i] = -0.02f - RANDOM.nextFloat() * 0.015f;
                return;
            }
            case WAR: {
                double sx = px + rnd(10), sy = py - 0.5 + RANDOM.nextDouble() * 3, sz = pz + rnd(10);
                if (!openAir(level, sx, sy, sz, false)) return;
                i = add(sx, sy, sz, EMBER, B_RISE, 50 + RANDOM.nextInt(50), 0.06f + RANDOM.nextFloat() * 0.06f, acc, 1f, true, false);
                if (i >= 0) {
                    vy[i] = 0.025f + RANDOM.nextFloat() * 0.035f;
                    vx[i] = (float) rnd(0.01);
                    vz[i] = (float) rnd(0.01);
                }
                return;
            }
            case DRIFT: {
                double sx = px + rnd(10), sy = py - 1 + RANDOM.nextDouble() * 4, sz = pz + rnd(10);
                if (!openAir(level, sx, sy, sz, false)) return;
                i = add(sx, sy, sz, GLOW, B_FLOAT, 100 + RANDOM.nextInt(60), 0.08f + RANDOM.nextFloat() * 0.06f,
                        RANDOM.nextBoolean() ? acc : prof.glow, 0.9f, true, false);
                if (i >= 0) {
                    vy[i] = 0.012f + RANDOM.nextFloat() * 0.018f; // carried upwards
                    vx[i] = (float) Math.cos(windAngle) * 0.018f;
                    vz[i] = (float) Math.sin(windAngle) * 0.018f;
                    behaviour[i] = B_STATIC; // keeps its current (no damping)
                }
                return;
            }
            case HARVEST: {
                if (!prof.flag("pollen")) return;
                double sx = px + rnd(8), sy = py + RANDOM.nextDouble() * 2.5, sz = pz + rnd(8);
                BlockPos bp = new BlockPos(sx, sy, sz);
                BlockState below = level.getBlockState(bp.below()), at = level.getBlockState(bp);
                boolean vegetation = at.getBlock() instanceof BushBlock || below.is(BlockTags.LEAVES) || below.is(Blocks.GRASS_BLOCK)
                        || below.is(BlockTags.CROPS) || at.is(BlockTags.FLOWERS);
                if (!vegetation || !openAir(level, sx, sy + 0.4, sz, false)) return;
                i = add(sx, sy + 0.4, sz, GLOW, B_FLOAT, 80 + RANDOM.nextInt(60), 0.05f + RANDOM.nextFloat() * 0.03f, acc, 0.9f, true, false);
                if (i >= 0) vy[i] = 0.004f;
                return;
            }
            case MIST:
            case BLOOD: {
                double ang = RANDOM.nextDouble() * Math.PI * 2, dist = 8 + RANDOM.nextDouble() * 12;
                double sx = px + Math.cos(ang) * dist, sy = py + RANDOM.nextDouble() * 2.5, sz = pz + Math.sin(ang) * dist;
                if (!openAir(level, sx, sy, sz, true)) return;
                float a = t == MoonType.MIST ? 0.2f : 0.12f;
                i = add(sx, sy, sz, WISP, B_STATIC, 220 + RANDOM.nextInt(100), 3f + RANDOM.nextFloat() * 3f, t == MoonType.MIST ? prof.horizon : acc, a, false, false);
                if (i >= 0) {
                    vx[i] = (float) rnd(0.015);
                    vz[i] = (float) rnd(0.015);
                    light[i] = skyLight(level, sx, sy, sz);
                }
                return;
            }
            case ARCANE: {
                boolean rune = prof.flag("runes") && RANDOM.nextInt(4) == 0;
                double sx = px + rnd(12), sy = py + 1 + RANDOM.nextDouble() * 4, sz = pz + rnd(12);
                if (!openAir(level, sx, sy, sz, false)) return;
                if (rune) {
                    add(sx, sy, sz, RANDOM.nextBoolean() ? RUNE_A : RUNE_B, B_STATIC, 60, 0.45f, acc, 0.8f, true, false);
                } else {
                    i = add(sx, sy, sz, SPARK, B_FLOAT, 50 + RANDOM.nextInt(40), 0.1f, prof.glow, 0.9f, true, false);
                    if (i >= 0) vy[i] = 0.006f;
                }
                return;
            }
            case PROSPECTOR: {
                double sx = px + rnd(8), sy = py + RANDOM.nextDouble() * 3, sz = pz + rnd(8);
                if (!openAir(level, sx, sy, sz, false)) return;
                i = add(sx, sy, sz, GLOW, B_FLOAT, 120 + RANDOM.nextInt(80), 0.035f + RANDOM.nextFloat() * 0.03f, prof.glow, 0.6f, false, false);
                if (i >= 0) {
                    vy[i] = -0.002f;
                    light[i] = skyLight(level, sx, sy, sz);
                }
                return;
            }
            case WISDOM:
            case BLUE:
            case REVEALING:
            case QUIET: {
                double sx = px + rnd(12), sy = py + (t == MoonType.BLUE ? 5 + RANDOM.nextDouble() * 8 : RANDOM.nextDouble() * 4), sz = pz + rnd(12);
                if (!openAir(level, sx, sy, sz, true)) return;
                int spr = t == MoonType.WISDOM || t == MoonType.BLUE ? SPARK : GLOW;
                i = add(sx, sy, sz, spr, t == MoonType.BLUE ? B_STATIC : B_FLOAT, 60 + RANDOM.nextInt(60), t == MoonType.BLUE ? 0.12f : 0.06f, acc, 0.8f, true, false);
                if (i >= 0 && t == MoonType.WISDOM) vy[i] = 0.006f;
                return;
            }
            case THREAD: {
                double sx = px + rnd(10), sy = py + 0.5 + RANDOM.nextDouble() * 3, sz = pz + rnd(10);
                if (!openAir(level, sx, sy, sz, false)) return;
                i = add(sx, sy, sz, SILK, B_FLOAT, 120 + RANDOM.nextInt(60), 0.35f, acc, 0.55f, true, false);
                if (i >= 0) vy[i] = -0.003f;
                return;
            }
            case GLUTTONY: {
                double sx = px + rnd(9), sy = py + RANDOM.nextDouble() * 3, sz = pz + rnd(9);
                if (!openAir(level, sx, sy, sz, false)) return;
                i = add(sx, sy, sz, GLOW, B_FLOAT, 80 + RANDOM.nextInt(60), 0.07f, acc, 0.85f, true, false);
                if (i >= 0) vy[i] = 0.005f;
                return;
            }
            default:
        }
    }

    /** Cold breath, ore glints, magic-block sparkles and steam: bounded samples, never area scans. */
    private static void effectsPerTick(Minecraft mc, ClientWorld level) {
        long now = Atmosphere.ticks();
        Entity p = mc.player;
        ClientConfig.Quality q = Atmosphere.quality();
        // Frost: cold breath
        float frost = Atmosphere.weight(MoonType.FROST);
        AtmoProfile fp = AtmoProfile.of(MoonType.FROST);
        if (frost > 0.5f && fp.particles && fp.flag("coldBreath") && q.ordinal() >= ClientConfig.Quality.MEDIUM.ordinal()
                && Atmosphere.exposure() > 0.6f && now >= nextBreath && mc.options.getCameraType().isFirstPerson()) {
            nextBreath = now + 70 + RANDOM.nextInt(60);
            Vector3d look = p.getLookAngle();
            Vector3d eye = p.getEyePosition(1f);
            for (int k = 0; k < 2; k++) {
                int i = add(eye.x + look.x * 0.45, eye.y - 0.18 + look.y * 0.3, eye.z + look.z * 0.45, WISP, B_BREATH, 28,
                        0.12f, new float[]{0.92f, 0.96f, 1f}, 0.22f, false, false);
                if (i >= 0) {
                    vx[i] = (float) (look.x * 0.025 + rnd(0.004));
                    vy[i] = 0.008f;
                    vz[i] = (float) (look.z * 0.025 + rnd(0.004));
                    grow[i] = 0.012f;
                    light[i] = 0.9f;
                }
            }
        }
        // Prospector: ore glints on exposed, visible ore faces
        AtmoProfile pp = AtmoProfile.of(MoonType.PROSPECTOR);
        if (Atmosphere.weight(MoonType.PROSPECTOR) > 0.3f && pp.particles && pp.flag("oreGlints")) {
            Vector3d eye = p.getEyePosition(1f);
            for (int k = 0; k < 6; k++) {
                BlockPos bp = new BlockPos(eye.x + rnd(9), eye.y + rnd(5), eye.z + rnd(9));
                if (!level.isLoaded(bp) || !level.getBlockState(bp).is(Tags.Blocks.ORES)) continue;
                Direction face = visibleFace(level, eye, bp, p);
                if (face == null) continue;
                remember(bp, face, now);
            }
            if (RANDOM.nextInt(8) == 0) {
                int slot = RANDOM.nextInt(glintPos.length);
                if (glintExpire[slot] > now) {
                    BlockPos bp = BlockPos.of(glintPos[slot]);
                    Direction f = Direction.from3DDataValue(glintFace[slot]);
                    if (level.getBlockState(bp).is(Tags.Blocks.ORES)) {
                        double gx = bp.getX() + 0.5 + f.getStepX() * 0.52 + (f.getStepX() == 0 ? rnd(0.35) : 0);
                        double gy = bp.getY() + 0.5 + f.getStepY() * 0.52 + (f.getStepY() == 0 ? rnd(0.35) : 0);
                        double gz = bp.getZ() + 0.5 + f.getStepZ() * 0.52 + (f.getStepZ() == 0 ? rnd(0.35) : 0);
                        add(gx, gy, gz, SPARK, B_STATIC, 14, 0.22f, pp.accent, 1f, true, true); // short; may replace drifting dust
                    } else {
                        glintExpire[slot] = 0;
                    }
                }
            }
        }
        // Arcane: sparkles near enchanting tables and bookshelves
        AtmoProfile ap = AtmoProfile.of(MoonType.ARCANE);
        if (Atmosphere.weight(MoonType.ARCANE) > 0.3f && ap.particles && ap.flag("magicBlockSparkles")) {
            for (int k = 0; k < 4; k++) {
                BlockPos bp = new BlockPos(p.getX() + rnd(8), p.getY() + rnd(3), p.getZ() + rnd(8));
                if (!level.isLoaded(bp)) continue;
                BlockState st = level.getBlockState(bp);
                if ((st.is(Blocks.ENCHANTING_TABLE) || st.is(Blocks.BOOKSHELF)) && level.isEmptyBlock(bp.above()) && RANDOM.nextInt(3) == 0) {
                    int i = add(bp.getX() + 0.5 + rnd(0.4), bp.getY() + 1.1, bp.getZ() + 0.5 + rnd(0.4), SPARK, B_FLOAT, 40, 0.09f, ap.accent, 0.9f, true, false);
                    if (i >= 0) vy[i] = 0.01f;
                }
            }
        }
        // Gluttony: steam above lit cooking blocks
        AtmoProfile gp = AtmoProfile.of(MoonType.GLUTTONY);
        if (Atmosphere.weight(MoonType.GLUTTONY) > 0.3f && gp.particles && gp.flag("steam")) {
            for (int k = 0; k < 5; k++) {
                BlockPos bp = new BlockPos(p.getX() + rnd(10), p.getY() + rnd(4), p.getZ() + rnd(10));
                if (!level.isLoaded(bp)) continue;
                if (isLitCooker(level.getBlockState(bp)) && level.isEmptyBlock(bp.above())) {
                    int slot = 0;
                    for (int j = 0; j < steamPos.length; j++) {
                        if (steamPos[j] == bp.asLong() || steamExpire[j] < steamExpire[slot]) slot = j;
                        if (steamPos[j] == bp.asLong()) break;
                    }
                    steamPos[slot] = bp.asLong();
                    steamExpire[slot] = now + 300;
                }
            }
            for (int j = 0; j < steamPos.length; j++) {
                if (steamExpire[j] <= now || RANDOM.nextInt(16) != 0) continue;
                BlockPos bp = BlockPos.of(steamPos[j]);
                if (!isLitCooker(level.getBlockState(bp))) {
                    steamExpire[j] = 0;
                    continue;
                }
                int i = add(bp.getX() + 0.5 + rnd(0.2), bp.getY() + 1.05, bp.getZ() + 0.5 + rnd(0.2), WISP, B_STEAM, 50, 0.25f,
                        new float[]{1f, 0.97f, 0.93f}, 0.28f, false, false);
                if (i >= 0) {
                    vy[i] = 0.02f;
                    grow[i] = 0.012f;
                    light[i] = skyLight(level, bp.getX() + 0.5, bp.getY() + 1.2, bp.getZ() + 0.5);
                }
            }
        }
    }

    private static boolean isLitCooker(BlockState st) {
        if (st.getBlock() instanceof AbstractFurnaceBlock) return st.getValue(AbstractFurnaceBlock.LIT);
        if (st.getBlock() instanceof CampfireBlock) return st.getValue(CampfireBlock.LIT);
        return false;
    }

    /** A face of the ore that touches air and that the player can see directly (ray from the eye hits this block). */
    private static Direction visibleFace(ClientWorld level, Vector3d eye, BlockPos ore, Entity viewer) {
        for (Direction d : Direction.values()) {
            BlockPos n = ore.relative(d);
            if (!level.isEmptyBlock(n)) continue;
            Vector3d faceCenter = new Vector3d(ore.getX() + 0.5 + d.getStepX() * 0.5, ore.getY() + 0.5 + d.getStepY() * 0.5, ore.getZ() + 0.5 + d.getStepZ() * 0.5);
            Vector3d toFace = faceCenter.subtract(eye);
            if (toFace.dot(new Vector3d(d.getStepX(), d.getStepY(), d.getStepZ())) >= 0) continue; // facing away from the player
            BlockRayTraceResult hit = level.clip(new RayTraceContext(eye, faceCenter.subtract(toFace.normalize().scale(-0.05)),
                    RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, viewer));
            if (hit.getType() == RayTraceResult.Type.BLOCK && hit.getBlockPos().equals(ore)) return d;
        }
        return null;
    }

    /** Ore blocks currently remembered for glints (tests): only exposed, visible faces ever get here. */
    public static java.util.List<BlockPos> glintBlocks() {
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int j = 0; j < glintPos.length; j++) if (glintExpire[j] > Atmosphere.ticks()) out.add(BlockPos.of(glintPos[j]));
        return out;
    }

    private static void remember(BlockPos bp, Direction face, long now) {
        int slot = 0;
        for (int j = 0; j < glintPos.length; j++) {
            if (glintPos[j] == bp.asLong() && glintExpire[j] > now) {
                slot = j;
                break;
            }
            if (glintExpire[j] < glintExpire[slot]) slot = j;
        }
        glintPos[slot] = bp.asLong();
        glintFace[slot] = face.get3DDataValue();
        glintExpire[slot] = now + 200;
    }

    // ---- reactions ---------------------------------------------------------------------------------------------

    /** Gluttony: a new food was counted (heart = it added a bonus heart). */
    public static void feastBurst(double px, double py, double pz, boolean heart) {
        if (!Atmosphere.on("enableParticles")) return;
        AtmoProfile gp = AtmoProfile.of(MoonType.GLUTTONY);
        if (!gp.flag("foodBursts")) return;
        int n = heart ? 14 : 8;
        for (int k = 0; k < n; k++) {
            double ang = k * Math.PI * 2 / n;
            int i = add(px, py + 1.0, pz, heart && (k & 1) == 0 ? SPARK : EMBER, B_BURST, 22 + RANDOM.nextInt(8),
                    heart ? 0.14f : 0.1f, gp.accent, 1f, true, true);
            if (i >= 0) {
                float sp = heart ? 0.16f : 0.11f;
                vx[i] = (float) Math.cos(ang) * sp;
                vz[i] = (float) Math.sin(ang) * sp;
                vy[i] = 0.02f + RANDOM.nextFloat() * 0.03f;
            }
        }
        // a short visual glow around the player (not block light)
        int i = add(px, py + 1.0, pz, GLOW, B_STATIC, 16, heart ? 2.6f : 1.8f, gp.accent, heart ? 0.45f : 0.3f, true, true);
        if (i >= 0) grow[i] = 0.05f;
    }

    /** Drift: nearby motes are lifted when the player jumps. */
    public static void jumpKick(double px, double py, double pz) {
        if (!AtmoProfile.of(MoonType.DRIFT).flag("reactToJumps")) return;
        for (int i = 0; i < count; i++) {
            double dx = x[i] - px, dy = y[i] - py, dz = z[i] - pz;
            if (dx * dx + dy * dy + dz * dz < 16) vy[i] += 0.05f;
        }
    }

    // ---- render ---------------------------------------------------------------------------------------------

    public static void render(MatrixStack ms, float pt) {
        lastRendered = 0;
        if (count == 0) return;
        Minecraft mc = Minecraft.getInstance();
        ActiveRenderInfo cam = mc.gameRenderer.getMainCamera();
        Vector3d c = cam.getPosition();
        Vector3f up = cam.getUpVector().copy();
        Vector3f left = cam.getLookVector().copy();
        left.cross(up);
        Matrix4f m = ms.last().pose();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
        RenderSystem.enableTexture();
        mc.getTextureManager().bind(SPRITES);
        RenderSystem.enableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.defaultAlphaFunc();
        RenderSystem.enableAlphaTest();
        for (int pass = 0; pass < 2; pass++) {
            boolean add = pass == 1;
            if (add) RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            else RenderSystem.defaultBlendFunc();
            BufferBuilder buf = Tessellator.getInstance().getBuilder();
            buf.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);
            int n = 0;
            for (int i = 0; i < count; i++) {
                if (additive[i] != add) continue;
                float px = (float) (x[i] + vx[i] * pt - c.x), py = (float) (y[i] + vy[i] * pt - c.y), pz = (float) (z[i] + vz[i] * pt - c.z);
                float s = size[i] + grow[i] * pt;
                float lifeF = (age[i] + pt) / life[i];
                float a = alpha[i] * Math.min(1f, Math.min(lifeF * 5f, (1f - lifeF) * 4f));
                if (sprite[i] == EMBER) a *= 0.7f + 0.3f * (float) Math.sin((age[i] + i) * 0.9);
                if (sprite[i] == SPARK && behaviour[i] == B_STATIC) a *= (float) Math.sin(Math.PI * lifeF);
                if (a <= 0.01f) continue;
                float l = light[i];
                float u0 = sprite[i] / 8f, u1 = (sprite[i] + 1) / 8f;
                float lx = left.x() * s, ly = left.y() * s, lz = left.z() * s;
                float ux = up.x() * s, uy = up.y() * s, uz = up.z() * s;
                float cr = r[i] * l, cg = g[i] * l, cb = b[i] * l;
                buf.vertex(m, px - lx - ux, py - ly - uy, pz - lz - uz).uv(u1, 1).color(cr, cg, cb, a).endVertex();
                buf.vertex(m, px - lx + ux, py - ly + uy, pz - lz + uz).uv(u1, 0).color(cr, cg, cb, a).endVertex();
                buf.vertex(m, px + lx + ux, py + ly + uy, pz + lz + uz).uv(u0, 0).color(cr, cg, cb, a).endVertex();
                buf.vertex(m, px + lx - ux, py + ly - uy, pz + lz - uz).uv(u0, 1).color(cr, cg, cb, a).endVertex();
                n++;
            }
            Tessellator.getInstance().end();
            lastRendered += n;
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
    }
}
