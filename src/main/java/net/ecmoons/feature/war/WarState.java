package net.ecmoons.feature.war;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Persisted War Moon session (data/ecmoons_war_session.dat). Only what is needed to recover after a crash:
 * the phase and every player who was taken out of the normal world. Their belongings are in WarRecords.
 */
public final class WarState extends WorldSavedData {
    public static final String NAME = "ecmoons_war_session";

    public enum Phase { IDLE, WARNING, PREP, FIGHT, SUDDEN_DEATH, ENDING }

    /** Set while the add-on itself moves players, so the arena guard does not fight it. */
    public static boolean allowTeleport;

    Phase phase = Phase.IDLE;
    final Set<UUID> involved = new LinkedHashSet<>();
    long lastTournamentDay = Long.MIN_VALUE;

    public WarState() {
        super(NAME);
    }

    public static WarState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(WarState::new, NAME);
    }

    public Phase phase() {
        return phase;
    }

    @Override
    public void load(CompoundNBT tag) {
        try {
            phase = Phase.valueOf(tag.getString("phase"));
        } catch (IllegalArgumentException e) {
            phase = Phase.IDLE;
        }
        involved.clear();
        ListNBT list = tag.getList("involved", Constants.NBT.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) involved.add(NBTUtil.loadUUID(list.get(i)));
        lastTournamentDay = tag.contains("lastDay") ? tag.getLong("lastDay") : Long.MIN_VALUE;
    }

    @Override
    public CompoundNBT save(CompoundNBT tag) {
        tag.putString("phase", phase.name());
        ListNBT list = new ListNBT();
        for (UUID id : involved) list.add(NBTUtil.createUUID(id));
        tag.put("involved", list);
        tag.putLong("lastDay", lastTournamentDay);
        return tag;
    }
}
