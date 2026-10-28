package net.ecmoons.feature.war;

import net.ecmoons.EcMoons;
import net.ecmoons.config.Settings;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3i;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.template.PlacementSettings;
import net.minecraft.world.gen.feature.template.Template;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The War Moon arena. It lives in its own void dimension (ecmoons:war_arena, defined in the add-on's data pack),
 * so nothing in the main worlds is touched. A fresh copy is built for every tournament and removed afterwards.
 * <p>
 * Built-in layout (floor size and wall height configurable): a square fighting floor with tall walls,
 * up to 16 separate 3x3 starting cells around the inside of the walls with iron-bar gates that open together,
 * symmetric pillars and low walls in the middle with several open crossing routes, and a sealed viewing
 * gallery above the arena with an invisible barrier floor. A structure template can replace the built-in layout.
 */
public final class WarArena {
    public static final RegistryKey<World> DIMENSION =
            RegistryKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(EcMoons.MOD_ID, "war_arena"));
    public static final int FLOOR_Y = 100;

    /** Positions of the built arena. */
    public static final class Layout {
        public final List<BlockPos> cells = new ArrayList<>();
        public final List<BlockPos> gates = new ArrayList<>();
        public BlockPos spectator;
        public BlockPos min, max;
        public int half;

        public BlockPos center() {
            return new BlockPos(0, FLOOR_Y + 1, 0);
        }
    }

    private WarArena() {}

    public static ServerWorld world(net.minecraft.server.MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static Layout build(ServerWorld world) {
        clear(world);
        Settings s = Settings.server();
        String template = s.s("war.arenaTemplate").trim();
        if (!template.isEmpty()) {
            Layout l = buildFromTemplate(world, template);
            if (l != null) return l;
            EcMoons.LOGGER.warn("War Moon: arena template '{}' could not be used - building the default arena", template);
        }
        return buildDefault(world, s.i("war.arenaFloorSize"), s.i("war.arenaWallHeight"));
    }

    private static void set(ServerWorld w, int x, int y, int z, BlockState state) {
        w.setBlock(new BlockPos(x, y, z), state, 2);
    }

    private static Layout buildDefault(ServerWorld w, int size, int wallHeight) {
        Layout l = new Layout();
        int h = size / 2;
        l.half = h;
        int y = FLOOR_Y;
        BlockState floor = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        BlockState light = Blocks.SEA_LANTERN.defaultBlockState();
        BlockState wall = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
        BlockState trim = Blocks.RED_NETHER_BRICKS.defaultBlockState();
        BlockState cover = Blocks.STONE_BRICKS.defaultBlockState();
        for (int x = -h - 1; x <= h; x++) {
            for (int z = -h - 1; z <= h; z++) {
                boolean edge = x == -h - 1 || x == h || z == -h - 1 || z == h;
                set(w, x, y - 1, z, Blocks.BEDROCK.defaultBlockState());
                set(w, x, y, z, (!edge && Math.floorMod(x, 6) == 0 && Math.floorMod(z, 6) == 0) ? light : floor);
                if (edge) {
                    for (int dy = 1; dy <= wallHeight; dy++) set(w, x, y + dy, z, dy == wallHeight ? trim : wall);
                    set(w, x, y + wallHeight + 1, z, Blocks.BARRIER.defaultBlockState());
                }
            }
        }
        // 16 starting cells: 4 per side, 3x3 inside, iron-bar gate facing the centre
        int[] offsets = {-3 * h / 4, -h / 4, h / 4, 3 * h / 4};
        for (int side = 0; side < 4; side++) {
            for (int o : offsets) {
                // cell centre, depth direction (dx,dz) pointing at the arena centre, width direction (wx,wz)
                int cx, cz, dx, dz;
                switch (side) {
                    case 0: cx = o; cz = -h + 1; dx = 0; dz = 1; break;
                    case 1: cx = h - 2; cz = o; dx = -1; dz = 0; break;
                    case 2: cx = o; cz = h - 2; dx = 0; dz = -1; break;
                    default: cx = -h + 1; cz = o; dx = 1; dz = 0; break;
                }
                int wx = dz, wz = dx; // perpendicular
                for (int u = -2; u <= 2; u++) {
                    for (int v = -2; v <= 2; v++) {
                        if (Math.abs(u) != 2 && Math.abs(v) != 2) continue; // interior
                        if (v == -2) continue;                                  // the outer wall
                        int bx = cx + u * wx + v * dx, bz = cz + u * wz + v * dz;
                        boolean gate = v == 2 && Math.abs(u) <= 1;
                        for (int dy = 1; dy <= 3; dy++) {
                            if (gate) {
                                set(w, bx, y + dy, bz, Blocks.IRON_BARS.defaultBlockState());
                                l.gates.add(new BlockPos(bx, y + dy, bz));
                            } else {
                                set(w, bx, y + dy, bz, cover);
                            }
                        }
                        set(w, bx, y + 4, bz, Blocks.BARRIER.defaultBlockState());
                    }
                }
                for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++) {
                    set(w, cx + u * wx + v * dx, y + 4, cz + u * wz + v * dz, Blocks.BARRIER.defaultBlockState());
                }
                l.cells.add(new BlockPos(cx, y + 1, cz));
            }
        }
        // cover: pillars and low walls, rotationally symmetric, centre left open
        int p = h / 3;
        int[][] pillars = {{p, p}, {-p, p}, {p, -p}, {-p, -p}, {0, 2 * p}, {0, -2 * p}, {2 * p, 0}, {-2 * p, 0}};
        for (int[] pp : pillars) {
            for (int a = 0; a <= 1; a++) for (int b = 0; b <= 1; b++) for (int dy = 1; dy <= 4; dy++) set(w, pp[0] + a, y + dy, pp[1] + b, cover);
        }
        int lw = Math.max(2, h / 6);
        int[][] lows = {{p + 4, 0, 0}, {-p - 4, 0, 0}, {0, p + 4, 1}, {0, -p - 4, 1}};
        for (int[] lo : lows) {
            for (int k = -lw; k <= lw; k++) {
                int x = lo[2] == 0 ? lo[0] : lo[0] + k, z = lo[2] == 0 ? lo[1] + k : lo[1];
                set(w, x, y + 1, z, Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState());
            }
        }
        // viewing gallery above the arena: invisible barrier floor, walls and roof (an unobstructed view down)
        int gy = y + wallHeight + 6, g = Math.max(6, h / 3);
        for (int x = -g; x <= g; x++) {
            for (int z = -g; z <= g; z++) {
                set(w, x, gy, z, Blocks.BARRIER.defaultBlockState());
                boolean edge = Math.abs(x) == g || Math.abs(z) == g;
                for (int dy = 1; dy <= 4; dy++) if (edge) set(w, x, gy + dy, z, Blocks.BARRIER.defaultBlockState());
                set(w, x, gy + 5, z, Blocks.BARRIER.defaultBlockState());
            }
        }
        l.spectator = new BlockPos(0, gy + 1, 0);
        l.min = new BlockPos(-h - 1, y - 1, -h - 1);
        l.max = new BlockPos(h, gy + 5, h);
        EcMoons.LOGGER.info("War Moon: arena built ({}x{}, {} cells)", size, size, l.cells.size());
        return l;
    }

    private static Layout buildFromTemplate(ServerWorld w, String id) {
        Template t = w.getStructureManager().get(new ResourceLocation(id));
        if (t == null) return null;
        Vector3i size = t.getSize();
        BlockPos origin = new BlockPos(-size.getX() / 2, FLOOR_Y - 1, -size.getZ() / 2);
        t.placeInWorld(w, origin, new PlacementSettings(), new Random(0));
        Layout l = new Layout();
        for (String c : Settings.server().list("war.arenaCells")) {
            BlockPos rel = parse(c);
            if (rel != null) l.cells.add(origin.offset(rel));
        }
        BlockPos sp = parse(Settings.server().s("war.arenaSpectatorPos"));
        if (l.cells.isEmpty() || sp == null) return null;
        l.spectator = origin.offset(sp);
        l.min = origin;
        l.max = origin.offset(size);
        l.half = Math.max(size.getX(), size.getZ()) / 2;
        return l;
    }

    private static BlockPos parse(String s) {
        try {
            String[] p = s.trim().split(",");
            return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void openGates(ServerWorld w, Layout l) {
        for (BlockPos p : l.gates) w.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
    }

    /** Remove every block of the arena region (the dimension is otherwise empty). */
    public static void clear(ServerWorld w) {
        int r = 60, top = FLOOR_Y + 60;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = FLOOR_Y - 2; y <= top; y++) {
                    m.set(x, y, z);
                    if (!w.getBlockState(m).isAir()) w.setBlock(m, air, 2);
                }
            }
        }
    }

    public static boolean inside(Layout l, BlockPos p) {
        return p.getX() >= l.min.getX() && p.getX() <= l.max.getX() && p.getZ() >= l.min.getZ() && p.getZ() <= l.max.getZ()
                && p.getY() >= l.min.getY() && p.getY() <= l.max.getY();
    }
}
