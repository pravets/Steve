package com.steve.ai.event;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.entity.SteveWorldData;
import com.steve.ai.memory.VisionScanner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SteveMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerEventHandler {

    /**
     * Register every Steve that enters the world (fresh spawn, chunk load,
     * dimension change) with the manager. Server-side only: client copies
     * must not be tracked. Dedup of world-loaded duplicates happens here too:
     * adopt() rejects a duplicate that has not entered the world yet, and
     * canceling the event keeps it from being added at all.
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof SteveEntity steve) {
            if (SteveMod.getSteveManager().adopt(steve) == null) {
                event.setCanceled(true);
            }
        }
    }

    /**
     * Drop the vision cache entry when a Steve leaves the level (despawn,
     * removal, dimension change) to avoid memory leaks. Untrack it from the
     * registries for every removal reason except a dimension change: a
     * CHANGED_DIMENSION bot is still alive (just in another level) and is
     * re-adopted idempotently when it joins the new level.
     */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof SteveEntity steve) {
            VisionScanner.forget(steve);
            if (!event.getLevel().isClientSide()
                    && steve.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION) {
                SteveMod.getSteveManager().onSteveUnload(steve);
            }
        }
    }

    /**
     * Periodic safety net: the manager cleans dead/removed Steve entries that
     * were not untracked by any leave-level event.
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            SteveMod.getSteveManager().tick();
        }
    }

    /**
     * Clear all vision caches on server shutdown.
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        VisionScanner.clearCache();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            SteveManager manager = SteveMod.getSteveManager();
            // The "default bots already spawned" marker lives in the world's
            // SavedData, so it survives chunk unloads and player logouts. The
            // default bots are spawned only on the very first world start -
            // never re-spawned on a later login (bug #9).
            SteveWorldData worldData = getWorldData(level);
            if (!worldData.hasDefaultBotsSpawned()) {
                // World-loaded Steves are already adopted by onEntityJoinLevel
                // and keep their NBT state (inventory, memory). spawnSteve()
                // skips names that already exist in the registry or in a
                // loaded chunk, so no duplicate is created over a world bot
                // that sits in a loaded chunk either.
                Vec3 playerPos = player.position();
                Vec3 lookVec = player.getLookAngle();

                String[] names = {"Steve", "Alex", "Bob", "Charlie"};

                for (int i = 0; i < 4; i++) {
                    double offsetX = lookVec.x * 5 + (lookVec.z * (i - 1.5) * 2);
                    double offsetZ = lookVec.z * 5 + (-lookVec.x * (i - 1.5) * 2);

                    Vec3 spawnPos = new Vec3(
                        playerPos.x + offsetX,
                        playerPos.y,
                        playerPos.z + offsetZ
                    );

                    manager.spawnSteve(level, spawnPos, names[i]);
                }

                worldData.markDefaultBotsSpawned();
            }
        }
    }

    /**
     * Returns the persistent {@link SteveWorldData} of the world the given
     * level belongs to. The overworld's data storage is used so the marker
     * is shared across dimensions. The entry is created on first access.
     */
    private static SteveWorldData getWorldData(ServerLevel level) {
        ServerLevel overworld = level.getServer() != null ? level.getServer().overworld() : level;
        return overworld.getDataStorage().computeIfAbsent(
            SteveWorldData::load, SteveWorldData::new, SteveWorldData.DATA_NAME);
    }
}

