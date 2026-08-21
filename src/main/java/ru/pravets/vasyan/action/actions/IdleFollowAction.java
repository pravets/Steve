package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.VasyanMod;
import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.entity.VasyanEntity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.List;

/**
 * Idle behavior for Vasyan - follows the nearest player when not working.
 * This action runs continuously until a task is given.
 * Teleports to player if too far away.
 */
public class IdleFollowAction extends BaseAction {
    private Player targetPlayer;
    private int ticksSincePlayerSearch;
    private static final int PLAYER_SEARCH_INTERVAL = 100; // Search for new player every 5 seconds
    private static final double FOLLOW_DISTANCE = 4.0; // Stay this far from player
    private static final double MIN_DISTANCE = 2.5; // Stop moving if closer than this
    private static final double TELEPORT_DISTANCE = 50.0; // Teleport if further than 50 blocks

    public IdleFollowAction(VasyanEntity vasyan) {
        super(vasyan, new Task("idle_follow", new HashMap<>()));
    }

    @Override
    protected void onStart() {
        ticksSincePlayerSearch = 0;
        findNearestPlayer();
        
        if (targetPlayer == null) {
            VasyanMod.LOGGER.debug("Vasyan '{}' has no player to follow (idle)", vasyan.getVasyanName());
        }
    }

    @Override
    protected void onTick() {
        ticksSincePlayerSearch++;
        
        // Periodically search for a better/closer player
        if (ticksSincePlayerSearch >= PLAYER_SEARCH_INTERVAL) {
            findNearestPlayer();
            ticksSincePlayerSearch = 0;
        }
        
        if (targetPlayer == null || !targetPlayer.isAlive() || targetPlayer.isRemoved()) {
            findNearestPlayer();
            if (targetPlayer == null) {
                // No players around, just stand idle
                vasyan.getNavigation().stop();
                return;
            }
        }
        
        // Follow the player at a comfortable distance
        double distance = vasyan.distanceTo(targetPlayer);
        if (distance > TELEPORT_DISTANCE) {
            // Teleport near the player (3-5 blocks away)
            double offsetX = (Math.random() - 0.5) * 6; // Random offset between -3 and +3
            double offsetZ = (Math.random() - 0.5) * 6;
            
            double targetX = targetPlayer.getX() + offsetX;
            double targetY = targetPlayer.getY();
            double targetZ = targetPlayer.getZ() + offsetZ;
            
            net.minecraft.core.BlockPos checkPos = new net.minecraft.core.BlockPos((int)targetX, (int)targetY, (int)targetZ);
            for (int i = 0; i < 10; i++) {
                net.minecraft.core.BlockPos groundPos = checkPos.below(i);
                if (!vasyan.level().getBlockState(groundPos).isAir() && 
                    vasyan.level().getBlockState(groundPos.above()).isAir()) {
                    // Found solid ground with air above
                    targetY = groundPos.above().getY();
                    break;
                }
            }
            
            vasyan.teleportTo(targetX, targetY, targetZ);
            vasyan.getNavigation().stop(); // Clear navigation after teleport
            
            VasyanMod.LOGGER.info("Vasyan '{}' teleported to player (was {} blocks away)", 
                vasyan.getVasyanName(), (int)distance);
            
        } else if (distance > FOLLOW_DISTANCE) {
            // Too far, move closer (normal walking)
            vasyan.getNavigation().moveTo(targetPlayer, 1.0);
        } else if (distance < MIN_DISTANCE) {
            // Too close, stop
            vasyan.getNavigation().stop();
        } else {
            if (!vasyan.getNavigation().isDone()) {
                vasyan.getNavigation().stop();
            }
        }
        
        // This action never completes on its own - it runs until cancelled
    }

    @Override
    protected void onCancel() {
        vasyan.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Following player (idle)";
    }

    /**
     * Find the nearest player to follow
     */
    private void findNearestPlayer() {
        List<? extends Player> players = vasyan.level().players();
        
        if (players.isEmpty()) {
            targetPlayer = null;
            return;
        }
        
        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            
            double distance = vasyan.distanceTo(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        
        if (nearest != targetPlayer && nearest != null) {
            VasyanMod.LOGGER.debug("Vasyan '{}' now following {} (idle)", 
                vasyan.getVasyanName(), nearest.getName().getString());
        }
        
        targetPlayer = nearest;
    }
}
