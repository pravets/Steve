package com.steve.ai.memory;

import com.steve.ai.SteveMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks all built structures to prevent overlapping builds
 */
public class StructureRegistry {
    private static final List<BuiltStructure> structures = new ArrayList<>();
    private static final int MIN_SPACING = 5; // Minimum blocks between structures
    
    public static class BuiltStructure {
        public final BlockPos position;
        public final int width;
        public final int height;
        public final int depth;
        public final String type;
        public final AABB bounds;
        
        public BuiltStructure(BlockPos pos, int width, int height, int depth, String type) {
            this.position = pos;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.type = type;
            
            this.bounds = new AABB(
                pos.getX() - MIN_SPACING,
                pos.getY() - MIN_SPACING,
                pos.getZ() - MIN_SPACING,
                pos.getX() + width + MIN_SPACING,
                pos.getY() + height + MIN_SPACING,
                pos.getZ() + depth + MIN_SPACING
            );
        }
        
        public boolean intersects(BlockPos testPos, int testWidth, int testHeight, int testDepth) {
            AABB testBounds = new AABB(
                testPos.getX(),
                testPos.getY(),
                testPos.getZ(),
                testPos.getX() + testWidth,
                testPos.getY() + testHeight,
                testPos.getZ() + testDepth
            );
            return bounds.intersects(testBounds);
        }
        
        public double distanceTo(BlockPos pos) {
            double dx = pos.getX() - position.getX();
            double dy = pos.getY() - position.getY();
            double dz = pos.getZ() - position.getZ();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
    
    /**
     * Register a newly built structure
     */
    public static void register(BlockPos pos, int width, int height, int depth, String type) {
        BuiltStructure structure = new BuiltStructure(pos, width, height, depth, type);
        structures.add(structure);
        SteveMod.LOGGER.info("Registered structure '{}' at {} ({}x{}x{})", type, pos, width, height, depth);
    }
    
    /**
     * Check if a position would conflict with existing structures
     */
    public static boolean hasConflict(BlockPos pos, int width, int height, int depth) {
        for (BuiltStructure structure : structures) {
            if (structure.intersects(pos, width, height, depth)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Find a clear position near the original position for building
     * Searches in expanding circles around the original position
     * Maintains the same Y level as the original position (already ground-adjusted)
     */
    public static BlockPos findClearPosition(BlockPos originalPos, int width, int height, int depth) {
        if (!hasConflict(originalPos, width, height, depth)) {
            return originalPos;
        }        int maxSearchRadius = 50; // Max 50 blocks away
        int searchStep = Math.max(width, depth) + MIN_SPACING; // Step by structure size + spacing
        
        for (int radius = searchStep; radius < maxSearchRadius; radius += searchStep) {
            for (int angle = 0; angle < 360; angle += 30) { // Check every 30 degrees
                double radians = Math.toRadians(angle);
                int offsetX = (int) (Math.cos(radians) * radius);
                int offsetZ = (int) (Math.sin(radians) * radius);
                
                BlockPos testPos = new BlockPos(
                    originalPos.getX() + offsetX,
                    originalPos.getY(),
                    originalPos.getZ() + offsetZ
                );
                
                if (!hasConflict(testPos, width, height, depth)) {
                    SteveMod.LOGGER.info("Found clear position at {} ({}m away)", testPos, radius);
                    return testPos;
                }
            }
        }
        
        BlockPos fallbackPos = new BlockPos(
            originalPos.getX() + maxSearchRadius,
            originalPos.getY(),
            originalPos.getZ()
        );
        SteveMod.LOGGER.warn("No clear position found, using fallback at {}", fallbackPos);
        return fallbackPos;
    }
    
    /**
     * Get all registered structures
     */
    public static List<BuiltStructure> getAllStructures() {
        return new ArrayList<>(structures);
    }
    
    /**
     * Get the closest structure to a position
     */
    public static BuiltStructure getClosest(BlockPos pos) {
        if (structures.isEmpty()) {
            return null;
        }
        
        BuiltStructure closest = structures.get(0);
        double minDistance = closest.distanceTo(pos);
        
        for (BuiltStructure structure : structures) {
            double distance = structure.distanceTo(pos);
            if (distance < minDistance) {
                minDistance = distance;
                closest = structure;
            }
        }
        
        return closest;
    }
    
    /**
     * Clear all registered structures (useful for cleanup)
     */
    public static void clear() {
        structures.clear();    }
    
    /**
     * Get count of registered structures
     */
    public static int getCount() {
        return structures.size();
    }
}

