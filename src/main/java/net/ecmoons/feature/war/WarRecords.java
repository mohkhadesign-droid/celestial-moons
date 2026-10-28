package net.ecmoons.feature.war;

import net.ecmoons.EcMoons;
import net.ecmoons.config.Settings;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.INBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.potion.EffectInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.FolderName;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Crash-safe recovery records for War Moon players: world/data/ecmoons_war/&lt;uuid&gt;.dat.
 * <p>
 * Order of operations (never reversed):
 * <ol>
 *   <li>capture everything restorable into a record and write it atomically (temp file + rename);</li>
 *   <li>read the file back and compare it with what was captured - only then is the player stripped;</li>
 *   <li>on return: clear the event items, load the saved inventory/accessories/XP/effects/state, teleport home;</li>
 *   <li>delete the record.</li>
 * </ol>
 * Restoring always replaces the inventory with the saved one (it never adds to it), so a record that is applied
 * twice (e.g. crash between steps 3 and 4) cannot duplicate items. A record survives restarts and is applied
 * when its player next logs in.
 */
public final class WarRecords {
    private WarRecords() {}

    /**
     * Forge's CapabilityProvider.serializeCaps/deserializeCaps are protected final (and not obfuscated), so they are
     * reached by reflection. This is the same data Forge writes to the player file under "ForgeCaps".
     */
    static final class Caps {
        private static java.lang.reflect.Method ser, deser;

        private static void init() throws ReflectiveOperationException {
            if (ser != null) return;
            Class<?> c = net.minecraftforge.common.capabilities.CapabilityProvider.class;
            java.lang.reflect.Method s = c.getDeclaredMethod("serializeCaps");
            java.lang.reflect.Method d = c.getDeclaredMethod("deserializeCaps", CompoundNBT.class);
            s.setAccessible(true);
            d.setAccessible(true);
            deser = d;
            ser = s;
        }

        @Nullable
        static CompoundNBT serialize(ServerPlayerEntity player) {
            try {
                init();
                return (CompoundNBT) ser.invoke(player);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("capabilities cannot be read", e);
            }
        }

        static void deserialize(ServerPlayerEntity player, CompoundNBT tag) {
            try {
                init();
                deser.invoke(player, tag);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("capabilities cannot be written", e);
            }
        }
    }

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(FolderName.ROOT).resolve("data").resolve("ecmoons_war");
    }

    private static File file(MinecraftServer server, UUID id) {
        return dir(server).resolve(id + ".dat").toFile();
    }

    public static boolean exists(MinecraftServer server, UUID id) {
        return file(server, id).exists();
    }

    public static List<UUID> all(MinecraftServer server) {
        List<UUID> out = new ArrayList<>();
        File[] files = dir(server).toFile().listFiles((d, n) -> n.endsWith(".dat"));
        if (files == null) return out;
        for (File f : files) {
            try {
                out.add(UUID.fromString(f.getName().substring(0, f.getName().length() - 4)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    @Nullable
    public static CompoundNBT read(MinecraftServer server, UUID id) {
        File f = file(server, id);
        if (!f.exists()) return null;
        try {
            return CompressedStreamTools.readCompressed(f);
        } catch (IOException e) {
            EcMoons.LOGGER.error("War Moon: cannot read recovery record {}: {}", f, e.toString());
            return null;
        }
    }

    private static boolean write(MinecraftServer server, UUID id, CompoundNBT tag) {
        try {
            Path d = dir(server);
            Files.createDirectories(d);
            Path tmp = d.resolve(id + ".tmp");
            CompressedStreamTools.writeCompressed(tag, tmp.toFile());
            Files.move(tmp, d.resolve(id + ".dat"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            CompoundNBT back = read(server, id);
            return tag.equals(back);
        } catch (IOException e) {
            EcMoons.LOGGER.error("War Moon: cannot write recovery record for {}: {}", id, e.toString());
            return false;
        }
    }

    public static void delete(MinecraftServer server, UUID id) {
        try {
            Files.deleteIfExists(dir(server).resolve(id + ".dat"));
        } catch (IOException e) {
            EcMoons.LOGGER.error("War Moon: cannot delete recovery record for {}: {}", id, e.toString());
        }
    }

    // ---- capture / strip ------------------------------------------------------------------------------------

    /** Accessory capabilities present on this player that the config says hold equipment. */
    private static CompoundNBT accessoryCaps(ServerPlayerEntity player) {
        CompoundNBT all = Caps.serialize(player);
        CompoundNBT out = new CompoundNBT();
        if (all == null) return out;
        for (String key : Settings.server().list("war.accessoryCapabilities")) {
            if (all.contains(key)) out.put(key, all.get(key).copy());
        }
        return out;
    }

    /**
     * Save the player. Returns null on success, or a reason the player cannot safely take part.
     */
    @Nullable
    public static String save(ServerPlayerEntity player, String role) {
        MinecraftServer server = player.getServer();
        if (server == null) return "no server";
        if (exists(server, player.getUUID())) return "an earlier War Moon recovery record is still pending (ask an admin: /ecmoons war recover)";
        CompoundNBT tag = new CompoundNBT();
        tag.putInt("version", 1);
        tag.putString("role", role);
        tag.putString("name", player.getScoreboardName());
        tag.put("inventory", player.inventory.save(new ListNBT()));
        CompoundNBT caps;
        try {
            caps = accessoryCaps(player);
            String gap = unsupportedItemStorage(player);
            if (gap != null) return gap;
        } catch (RuntimeException e) {
            return "accessory slots could not be saved (" + e.getClass().getSimpleName() + ")";
        }
        tag.put("caps", caps);
        tag.putInt("xpLevel", player.experienceLevel);
        tag.putFloat("xpProgress", player.experienceProgress);
        tag.putInt("xpTotal", player.totalExperience);
        tag.putFloat("health", player.getHealth());
        tag.putInt("food", player.getFoodData().getFoodLevel());
        tag.putFloat("saturation", player.getFoodData().getSaturationLevel());
        tag.putInt("air", player.getAirSupply());
        ListNBT effects = new ListNBT();
        for (EffectInstance e : player.getActiveEffects()) effects.add(e.save(new CompoundNBT()));
        tag.put("effects", effects);
        tag.putInt("gameMode", player.gameMode.getGameModeForPlayer().getId());
        tag.putBoolean("mayfly", player.abilities.mayfly);
        tag.putBoolean("flying", player.abilities.flying);
        tag.putString("dimension", player.level.dimension().location().toString());
        tag.putDouble("x", player.getX());
        tag.putDouble("y", player.getY());
        tag.putDouble("z", player.getZ());
        tag.putFloat("yRot", player.yRot);
        tag.putFloat("xRot", player.xRot);
        if (!write(server, player.getUUID(), tag)) return "the recovery record could not be written and verified";
        return null;
    }

    /**
     * Any other player capability that holds items is equipment the add-on does not know how to store safely
     * (e.g. an accessory mod not listed in war.accessoryCapabilities). Such players are not enrolled.
     */
    @Nullable
    private static String unsupportedItemStorage(ServerPlayerEntity player) {
        CompoundNBT all = Caps.serialize(player);
        if (all == null) return null;
        List<String> known = Settings.server().list("war.accessoryCapabilities");
        List<String> ignored = Settings.server().list("war.ignoredCapabilities");
        for (String key : all.getAllKeys()) {
            if (known.contains(key) || ignored.contains(key)) continue;
            if (containsItems(all.get(key))) {
                EcMoons.LOGGER.warn("War Moon: {} has items in capability '{}' which is not in war.accessoryCapabilities", player.getScoreboardName(), key);
                return "your items in '" + key + "' cannot be stored safely (an admin can add it to war.accessoryCapabilities)";
            }
        }
        return null;
    }

    static boolean containsItems(INBT nbt) {
        if (isItem(nbt)) return !"minecraft:air".equals(((CompoundNBT) nbt).getString("id"));
        if (nbt instanceof CompoundNBT) {
            for (String k : ((CompoundNBT) nbt).getAllKeys()) if (containsItems(((CompoundNBT) nbt).get(k))) return true;
        } else if (nbt instanceof ListNBT) {
            for (INBT child : (ListNBT) nbt) if (containsItems(child)) return true;
        }
        return false;
    }

    /** Remove everything the player brought; only called after save() confirmed the record. */
    public static void strip(ServerPlayerEntity player) {
        player.inventory.clearContent();
        CompoundNBT caps = accessoryCaps(player);
        if (!caps.isEmpty()) {
            CompoundNBT emptied = caps.copy();
            for (String key : emptied.getAllKeys()) emptied.put(key, stripItems(emptied.get(key)));
            Caps.deserialize(player, emptied);
        }
        player.removeAllEffects();
        player.clearFire();
        player.abilities.mayfly = false;
        player.abilities.flying = false;
        player.setGameMode(GameType.ADVENTURE);
        player.onUpdateAbilities();
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5f);
        player.inventoryMenu.broadcastChanges();
    }

    /** Recursively remove item stacks (compounds with "id" and "Count") from accessory-mod NBT. */
    static INBT stripItems(INBT nbt) {
        if (nbt instanceof CompoundNBT) {
            CompoundNBT c = ((CompoundNBT) nbt).copy();
            for (String key : new ArrayList<>(c.getAllKeys())) {
                INBT child = c.get(key);
                if (isItem(child)) c.remove(key);
                else c.put(key, stripItems(child));
            }
            return c;
        }
        if (nbt instanceof ListNBT) {
            ListNBT in = (ListNBT) nbt;
            ListNBT out = new ListNBT();
            for (INBT child : in) {
                if (!isItem(child)) out.add(stripItems(child));
            }
            return out;
        }
        return nbt;
    }

    private static boolean isItem(INBT nbt) {
        return nbt instanceof CompoundNBT && ((CompoundNBT) nbt).contains("id", Constants.NBT.TAG_STRING)
                && ((CompoundNBT) nbt).contains("Count");
    }

    // ---- restore -----------------------------------------------------------------------------------------------

    /** Apply the player's record (if any). Returns true when a record was applied and removed. */
    public static boolean restore(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        CompoundNBT tag = read(server, player.getUUID());
        if (tag == null) return false;
        WarState.allowTeleport = true;
        try {
            player.inventory.clearContent();
            player.inventory.load(tag.getList("inventory", Constants.NBT.TAG_COMPOUND));
            CompoundNBT caps = tag.getCompound("caps");
            if (!caps.isEmpty()) Caps.deserialize(player, caps);
            player.removeAllEffects();
            ListNBT effects = tag.getList("effects", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < effects.size(); i++) {
                EffectInstance e = EffectInstance.load(effects.getCompound(i));
                if (e != null) player.addEffect(e);
            }
            player.setGameMode(GameType.byId(tag.getInt("gameMode")));
            player.abilities.mayfly = tag.getBoolean("mayfly") || player.abilities.mayfly;
            player.abilities.flying = tag.getBoolean("flying") && player.abilities.mayfly;
            player.onUpdateAbilities();
            player.setExperienceLevels(tag.getInt("xpLevel"));
            player.setExperiencePoints(Math.round(tag.getFloat("xpProgress") * player.getXpNeededForNextLevel()));
            player.totalExperience = tag.getInt("xpTotal");
            player.getFoodData().setFoodLevel(tag.getInt("food"));
            player.getFoodData().setSaturation(tag.getFloat("saturation"));
            player.setAirSupply(tag.getInt("air"));
            player.inventoryMenu.broadcastChanges();
            teleportHome(player, tag);
            player.setHealth(Math.max(1f, Math.min(tag.getFloat("health"), player.getMaxHealth())));
        } finally {
            WarState.allowTeleport = false;
        }
        delete(server, player.getUUID());
        EcMoons.LOGGER.info("War Moon: restored {} from their recovery record", player.getScoreboardName());
        return true;
    }

    private static void teleportHome(ServerPlayerEntity player, CompoundNBT tag) {
        MinecraftServer server = player.getServer();
        RegistryKey<World> key = RegistryKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(tag.getString("dimension")));
        ServerWorld world = server.getLevel(key);
        double x = tag.getDouble("x"), y = tag.getDouble("y"), z = tag.getDouble("z");
        if (world == null || key.equals(WarArena.DIMENSION)) {
            world = server.overworld();
            BlockPos spawn = world.getSharedSpawnPos();
            x = spawn.getX() + 0.5;
            y = spawn.getY();
            z = spawn.getZ() + 0.5;
        }
        BlockPos safe = safeSpot(world, new BlockPos(x, y, z));
        if (safe == null) {
            BlockPos spawn = server.overworld().getSharedSpawnPos();
            world = server.overworld();
            safe = safeSpot(world, spawn);
            if (safe == null) safe = world.getHeightmapPos(net.minecraft.world.gen.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawn);
            x = safe.getX() + 0.5;
            z = safe.getZ() + 0.5;
        } else if (!safe.equals(new BlockPos(x, y, z))) {
            x = safe.getX() + 0.5;
            z = safe.getZ() + 0.5;
        }
        player.teleportTo(world, x, safe.getY(), z, tag.getFloat("yRot"), tag.getFloat("xRot"));
    }

    /** The original spot if the player fits there, else the nearest spot above with two free blocks and a floor. */
    @Nullable
    static BlockPos safeSpot(ServerWorld world, BlockPos pos) {
        for (int dy = 0; dy <= 24; dy++) {
            BlockPos p = pos.above(dy);
            if (p.getY() > world.getMaxBuildHeight() - 2) break;
            if (fits(world, p)) return p;
        }
        return null;
    }

    private static boolean fits(ServerWorld world, BlockPos p) {
        BlockState feet = world.getBlockState(p), head = world.getBlockState(p.above()), below = world.getBlockState(p.below());
        return feet.getCollisionShape(world, p).isEmpty() && head.getCollisionShape(world, p.above()).isEmpty()
                && !feet.getMaterial().isLiquid() && (!below.getCollisionShape(world, p.below()).isEmpty() || below.getMaterial().isLiquid());
    }
}
