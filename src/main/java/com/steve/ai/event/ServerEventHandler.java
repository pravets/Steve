package com.steve.ai.event;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import com.steve.ai.memory.StructureRegistry;
import com.steve.ai.memory.VisionScanner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SteveMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerEventHandler {
    private static boolean stevesSpawned = false;

    /**
     * Drop the vision cache entry when a Steve leaves the level
     * (despawn, removal, dimension change) to avoid memory leaks.
     */
    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof SteveEntity steve) {
            VisionScanner.forget(steve);
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
                // Issue #9: NEVER wipe world Steves on login - that left
                // duplicates in chunks that were not loaded at this moment.
                // Adopt what the world already has (done by manager.tick),
                // and only spawn the default squad when no Steves exist.
                StructureRegistry.clear();
                if (manager.getActiveCount() == 0) {
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

