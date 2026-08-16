package com.steve.ai.event;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
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
    private static boolean stevesSpawned = false;

    /**
     * Register every Steve that enters the world (fresh spawn, chunk load,
     * dimension change) with the manager. Server-side only: client copies
     * must not be tracked. Dedup of world-loaded duplicates happens here too.
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (event.getEntity() instanceof SteveEntity steve) {
            SteveMod.getSteveManager().adopt(steve);
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
            if (!stevesSpawned) {
                // World-loaded Steves are already adopted by onEntityJoinLevel
                // and keep their NBT state (inventory, memory). Spawn the
                // default bots only for names that are not taken anywhere.
                Vec3 playerPos = player.position();
                Vec3 lookVec = player.getLookAngle();

                String[] names = {"Steve", "Alex", "Bob", "Charlie"};

                for (int i = 0; i < 4; i++) {
                    if (manager.getSteve(names[i]) != null) {
                        continue;
                    }
                    double offsetX = lookVec.x * 5 + (lookVec.z * (i - 1.5) * 2);
                    double offsetZ = lookVec.z * 5 + (-lookVec.x * (i - 1.5) * 2);

                    Vec3 spawnPos = new Vec3(
                        playerPos.x + offsetX,
                        playerPos.y,
                        playerPos.z + offsetZ
                    );

                    manager.spawnSteve(level, spawnPos, names[i]);
                }

                stevesSpawned = true;
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        stevesSpawned = false;
    }
}

