package ru.pravets.vasyan.action.actions;

import ru.pravets.vasyan.action.ActionResult;
import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.entity.VasyanEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class CombatAction extends BaseAction {
    private String targetType;
    private LivingEntity target;
    private int ticksRunning;
    private int ticksStuck;
    private double lastX, lastZ;
    private static final int MAX_TICKS = 600;
    private static final double ATTACK_RANGE = 3.5;

    public CombatAction(VasyanEntity vasyan, Task task) {
        super(vasyan, task);
    }

    @Override
    protected void onStart() {
        targetType = task.getStringParameter("target");
        ticksRunning = 0;
        ticksStuck = 0;
        
        // Make sure we're not flying (in case we were building)
        vasyan.setFlying(false);
        
        vasyan.setInvulnerableBuilding(true);
        
        findTarget();
        
        if (target == null) {
            ru.pravets.vasyan.VasyanMod.LOGGER.warn("Vasyan '{}' no targets nearby", vasyan.getVasyanName());
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        
        if (ticksRunning > MAX_TICKS) {
            // Combat complete - clean up and disable invulnerability
            vasyan.setInvulnerableBuilding(false);
            vasyan.setSprinting(false);
            vasyan.getNavigation().stop();
            ru.pravets.vasyan.VasyanMod.LOGGER.info("Vasyan '{}' combat complete, invulnerability disabled", 
                vasyan.getVasyanName());
            result = ActionResult.success("Combat complete");
            return;
        }
        
        // Re-search for targets periodically or if current target is invalid
        if (target == null || !target.isAlive() || target.isRemoved()) {
            if (ticksRunning % 20 == 0) {
                findTarget();
            }
            if (target == null) {
                return; // Keep searching
            }
        }
        
        double distance = vasyan.distanceTo(target);
        
        vasyan.setSprinting(true);
        vasyan.getNavigation().moveTo(target, 2.5); // High speed multiplier for sprinting
        
        double currentX = vasyan.getX();
        double currentZ = vasyan.getZ();
        if (Math.abs(currentX - lastX) < 0.1 && Math.abs(currentZ - lastZ) < 0.1) {
            ticksStuck++;
            
            if (ticksStuck > 40 && distance > ATTACK_RANGE) {
                // Teleport 4 blocks closer to target
                double dx = target.getX() - vasyan.getX();
                double dz = target.getZ() - vasyan.getZ();
                double dist = Math.sqrt(dx*dx + dz*dz);
                double moveAmount = Math.min(4.0, dist - ATTACK_RANGE);
                
                vasyan.teleportTo(
                    vasyan.getX() + (dx/dist) * moveAmount,
                    vasyan.getY(),
                    vasyan.getZ() + (dz/dist) * moveAmount
                );
                ticksStuck = 0;
                ru.pravets.vasyan.VasyanMod.LOGGER.info("Vasyan '{}' was stuck, teleported closer to target", 
                    vasyan.getVasyanName());
            }
        } else {
            ticksStuck = 0;
        }
        lastX = currentX;
        lastZ = currentZ;
        
        if (distance <= ATTACK_RANGE) {
            vasyan.doHurtTarget(target);
            vasyan.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
            
            // Attack 3 times per second (every 6-7 ticks)
            if (ticksRunning % 7 == 0) {
                vasyan.doHurtTarget(target);
            }
        }
    }

    @Override
    protected void onCancel() {
        vasyan.setInvulnerableBuilding(false);
        vasyan.getNavigation().stop();
        vasyan.setSprinting(false);
        vasyan.setFlying(false);
        target = null;
        ru.pravets.vasyan.VasyanMod.LOGGER.info("Vasyan '{}' combat cancelled, invulnerability disabled", 
            vasyan.getVasyanName());
    }

    @Override
    public String getDescription() {
        return "Attack " + targetType;
    }

    private void findTarget() {
        AABB searchBox = vasyan.getBoundingBox().inflate(32.0);
        List<Entity> entities = vasyan.level().getEntities(vasyan, searchBox);
        
        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity living && isValidTarget(living)) {
                double distance = vasyan.distanceTo(living);
                if (distance < nearestDistance) {
                    nearest = living;
                    nearestDistance = distance;
                }
            }
        }
        
        target = nearest;
        if (target != null) {
            ru.pravets.vasyan.VasyanMod.LOGGER.info("Vasyan '{}' locked onto: {} at {}m", 
                vasyan.getVasyanName(), target.getType().toString(), (int)nearestDistance);
        }
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (!entity.isAlive() || entity.isRemoved()) {
            return false;
        }
        
        // Don't attack other Vasyans or players
        if (entity instanceof VasyanEntity || entity instanceof net.minecraft.world.entity.player.Player) {
            return false;
        }
        
        String targetLower = targetType.toLowerCase();
        
        // Match ANY hostile mob
        if (targetLower.contains("mob") || targetLower.contains("hostile") || 
            targetLower.contains("monster") || targetLower.equals("any")) {
            return entity instanceof Monster;
        }
        
        // Match specific entity type
        String entityTypeName = entity.getType().toString().toLowerCase();
        return entityTypeName.contains(targetLower);
    }
}
