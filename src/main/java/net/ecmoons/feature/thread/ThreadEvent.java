package net.ecmoons.feature.thread;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.storage.WorldSavedData;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One Thread Moon event per dimension: a server-issued id (new each time the moon rises, kept when the server
 * restarts while the same moon is still up) and the players who have spent their Last Stitch during it. Saved with the
 * dimension, so relogging, dying, changing team or dimension, or a restart never refresh a spent rescue.
 */
public final class ThreadEvent extends WorldSavedData {
    public static final String NAME = "ecmoons_thread_event";
    private String id = "";
    private boolean active;
    private final Set<UUID> spent = new HashSet<>();

    public ThreadEvent() {
        super(NAME);
    }

    public static ThreadEvent get(ServerWorld w) {
        return w.getDataStorage().computeIfAbsent(ThreadEvent::new, NAME);
    }

    /** Called every tick with the moon state: a new id when the moon rises, the old one kept while it stays up. */
    public void update(ServerWorld w, boolean up) {
        if (up && !active) {
            active = true;
            id = w.dimension().location() + "@" + w.getGameTime();
            spent.clear();
            setDirty();
        } else if (!up && active) {
            active = false;
            setDirty();
        }
    }

    public String id() {
        return id;
    }

    public boolean active() {
        return active;
    }

    public boolean spent(UUID player) {
        return spent.contains(player);
    }

    public void spend(UUID a, UUID b) {
        spent.add(a);
        spent.add(b);
        setDirty();
    }

    /** Tests and admins: forget this event's rescues. */
    public void resetSpent() {
        spent.clear();
        setDirty();
    }

    @Override
    public void load(CompoundNBT tag) {
        id = tag.getString("id");
        active = tag.getBoolean("active");
        spent.clear();
        ListNBT list = tag.getList("spent", 8);
        for (int i = 0; i < list.size(); i++) {
            try {
                spent.add(UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @Override
    public CompoundNBT save(CompoundNBT tag) {
        tag.putString("id", id);
        tag.putBoolean("active", active);
        ListNBT list = new ListNBT();
        for (UUID u : spent) list.add(StringNBT.valueOf(u.toString()));
        tag.put("spent", list);
        return tag;
    }
}
