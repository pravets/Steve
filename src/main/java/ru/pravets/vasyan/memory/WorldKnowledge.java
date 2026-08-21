package com.steve.ai.memory;

import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;

import java.util.*;

public class WorldKnowledge {
    private final SteveEntity steve;
    private final int scanRadius = 16;
    private List<Entity> nearbyEntities;
    private String biomeName;

    public WorldKnowledge(SteveEntity steve) {
        this.steve = steve;
        scan();
    }

    private void scan() {
        scanBiome();
        scanEntities();
    }

    private void scanBiome() {
        Level level = steve.level();
        BlockPos pos = steve.blockPosition();
        
        Biome biome = level.getBiome(pos).value();
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var biomeKey = biomeRegistry.getKey(biome);
        
        if (biomeKey != null) {
            biomeName = biomeKey.getPath();
        } else {
            biomeName = "unknown";
        }
    }

    private void scanEntities() {
        Level level = steve.level();
        AABB searchBox = steve.getBoundingBox().inflate(scanRadius);
        nearbyEntities = level.getEntities(steve, searchBox);
    }

    public String getBiomeName() {
        return biomeName;
    }

    public String getNearbyBlocksSummary() {
        // Honest vision: only visible blocks with line of sight
        return VisionScanner.getVisibleSummary(steve);
    }

    public String getNearbyEntitiesSummary() {
        if (nearbyEntities.isEmpty()) {
            return "none";
        }
        
        Map<String, Integer> entityCounts = new HashMap<>();
        for (Entity entity : nearbyEntities) {
            String name = entity.getType().toString();
            entityCounts.put(name, entityCounts.getOrDefault(name, 0) + 1);
        }
        
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Integer> entry : entityCounts.entrySet()) {
            if (count > 0) sb.append(", ");
            sb.append(entry.getValue()).append(" ").append(entry.getKey());
            count++;
            if (count >= 5) break;
        }
        
        return sb.toString();
    }

    public String getNearbyPlayerNames() {
        List<String> playerNames = new ArrayList<>();
        for (Entity entity : nearbyEntities) {
            if (entity instanceof Player player) {
                playerNames.add(player.getName().getString());
            }
        }
        
        if (playerNames.isEmpty()) {
            return "none";
        }
        
        return String.join(", ", playerNames);
    }
}

