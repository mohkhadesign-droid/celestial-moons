package net.ecmoons.feature;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Administrator-managed moon teams (kingdoms), stored with the world in data/ecmoons_teams.dat.
 * Membership is by player UUID, one team per player. Nothing is inferred from chat colours, name prefixes,
 * scoreboard teams or claim membership.
 */
public final class MoonTeams extends WorldSavedData {
    public static final String NAME = "ecmoons_teams";

    private final Map<String, String> teams = new LinkedHashMap<>();   // id -> display name
    private final Map<UUID, String> members = new HashMap<>();         // player -> team id
    private final Map<UUID, String> memberNames = new HashMap<>();     // last known name, for listings

    public MoonTeams() {
        super(NAME);
    }

    public static MoonTeams get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(MoonTeams::new, NAME);
    }

    public boolean create(String id, String displayName) {
        if (teams.containsKey(id)) return false;
        teams.put(id, displayName);
        setDirty();
        return true;
    }

    public boolean delete(String id) {
        if (teams.remove(id) == null) return false;
        members.values().removeIf(id::equals);
        setDirty();
        return true;
    }

    /** Assign (moving the player out of any previous team). */
    public boolean assign(UUID player, String name, String teamId) {
        if (!teams.containsKey(teamId)) return false;
        members.put(player, teamId);
        memberNames.put(player, name);
        setDirty();
        return true;
    }

    public boolean remove(UUID player) {
        if (members.remove(player) == null) return false;
        setDirty();
        return true;
    }

    @Nullable
    public String teamOf(UUID player) {
        return members.get(player);
    }

    @Nullable
    public String displayName(@Nullable String teamId) {
        return teamId == null ? null : teams.get(teamId);
    }

    public Map<String, String> teams() {
        return Collections.unmodifiableMap(teams);
    }

    public Map<UUID, String> membersOf(String teamId) {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> e : members.entrySet()) {
            if (e.getValue().equals(teamId)) out.put(e.getKey(), memberNames.getOrDefault(e.getKey(), e.getKey().toString()));
        }
        return out;
    }

    public boolean sameTeam(UUID a, UUID b) {
        String ta = members.get(a);
        return ta != null && ta.equals(members.get(b));
    }

    @Override
    public void load(CompoundNBT tag) {
        teams.clear();
        members.clear();
        memberNames.clear();
        ListNBT t = tag.getList("teams", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < t.size(); i++) {
            CompoundNBT c = t.getCompound(i);
            teams.put(c.getString("id"), c.getString("name"));
        }
        ListNBT m = tag.getList("members", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < m.size(); i++) {
            CompoundNBT c = m.getCompound(i);
            UUID id = c.getUUID("uuid");
            if (teams.containsKey(c.getString("team"))) {
                members.put(id, c.getString("team"));
                memberNames.put(id, c.getString("name"));
            }
        }
    }

    @Override
    public CompoundNBT save(CompoundNBT tag) {
        ListNBT t = new ListNBT();
        for (Map.Entry<String, String> e : teams.entrySet()) {
            CompoundNBT c = new CompoundNBT();
            c.putString("id", e.getKey());
            c.putString("name", e.getValue());
            t.add(c);
        }
        ListNBT m = new ListNBT();
        for (Map.Entry<UUID, String> e : members.entrySet()) {
            CompoundNBT c = new CompoundNBT();
            c.putUUID("uuid", e.getKey());
            c.putString("team", e.getValue());
            c.putString("name", memberNames.getOrDefault(e.getKey(), ""));
            m.add(c);
        }
        tag.put("teams", t);
        tag.put("members", m);
        return tag;
    }
}
