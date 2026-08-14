package com.steve.ai.memory;

import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Honest vision for Steve: scans the world for interesting blocks (trees, ores,
 * chests) but only reports blocks with a clear line of sight (no x-ray, no cheats).
 *
 * <p>Scans run on demand and results are cached per-Steve for a few ticks
 * ({@code vision.scanCacheTicks}) so we don't hammer the server with raycasts.</p>
 */
public final class VisionScanner {

    /** Block types Steve considers "interesting" when looking around. */
    private static final Set<Block> INTERESTING = new HashSet<>();

    static {
        // Trees (logs)
        INTERESTING.add(Blocks.OAK_LOG);
        INTERESTING.add(Blocks.BIRCH_LOG);
        INTERESTING.add(Blocks.SPRUCE_LOG);
        INTERESTING.add(Blocks.JUNGLE_LOG);
        INTERESTING.add(Blocks.ACACIA_LOG);
        INTERESTING.add(Blocks.DARK_OAK_LOG);
        INTERESTING.add(Blocks.MANGROVE_LOG);
        INTERESTING.add(Blocks.CHERRY_LOG);
        INTERESTING.add(Blocks.CRIMSON_STEM);
        INTERESTING.add(Blocks.WARPED_STEM);

        // Ores (overworld)
        INTERESTING.add(Blocks.COAL_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_COAL_ORE);
        INTERESTING.add(Blocks.IRON_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_IRON_ORE);
        INTERESTING.add(Blocks.COPPER_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_COPPER_ORE);
        INTERESTING.add(Blocks.GOLD_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_GOLD_ORE);
        INTERESTING.add(Blocks.REDSTONE_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_REDSTONE_ORE);
        INTERESTING.add(Blocks.LAPIS_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_LAPIS_ORE);
        INTERESTING.add(Blocks.DIAMOND_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_DIAMOND_ORE);
        INTERESTING.add(Blocks.EMERALD_ORE);
        INTERESTING.add(Blocks.DEEPSLATE_EMERALD_ORE);

        // Storage
        INTERESTING.add(Blocks.CHEST);
        INTERESTING.add(Blocks.TRAPPED_CHEST);
        INTERESTING.add(Blocks.BARREL);
    }

    private record ScanCache(long cachedAtTick, Map<Block, List<BlockPos>> visible) {}

    private static final Map<SteveEntity, ScanCache> CACHE = new ConcurrentHashMap<>();

    private VisionScanner() {}

    /**
     * Finds all visible blocks of the given type near Steve, nearest first.
     * Returns an empty list if nothing is visible.
     */
    public static List<BlockPos> findVisible(SteveEntity steve, Block target) {
        Map<Block, List<BlockPos>> visible = scan(steve);
        List<BlockPos> found = visible.getOrDefault(target, List.of());
        if (found.isEmpty()) {
            return List.of();
        }
        BlockPos center = steve.blockPosition();
        return found.stream()
            .sorted(Comparator.comparingDouble(p -> p.distSqr(center)))
            .toList();
    }

    /**
     * Finds the nearest visible block of the given type, or null.
     */
    public static BlockPos findNearestVisible(SteveEntity steve, Block target) {
        List<BlockPos> found = findVisible(steve, target);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Human-readable summary of what Steve can see, for the LLM prompt.
     * Example: "oak_log x3 (12m S), iron_ore (8m down), chest (20m W)"
     */
    public static String getVisibleSummary(SteveEntity steve) {
        Map<Block, List<BlockPos>> visible = scan(steve);
        if (visible.isEmpty()) {
            return "nothing interesting";
        }

        BlockPos center = steve.blockPosition();
        List<String> parts = new ArrayList<>();

        visible.entrySet().stream()
            .sorted((a, b) -> {
                BlockPos na = VisionUtils.nearestOf(a.getValue(), center);
                BlockPos nb = VisionUtils.nearestOf(b.getValue(), center);
                return Double.compare(center.distSqr(na), center.distSqr(nb));
            })
            .limit(8)
            .forEach(entry -> {
                Block block = entry.getKey();
                List<BlockPos> positions = entry.getValue();
                BlockPos nearest = VisionUtils.nearestOf(positions, center);
                int distance = (int) Math.round(Math.sqrt(center.distSqr(nearest)));
                String direction = VisionUtils.directionTo(center, nearest);
                parts.add(block.getName().getString() + " x" + positions.size()
                    + " (" + distance + "m " + direction + ")");
            });

        return String.join(", ", parts);
    }

    /**
     * Checks whether Steve has a clear line of sight to the given block.
     */
    public static boolean hasLineOfSight(SteveEntity steve, BlockPos target) {
        Level level = steve.level();
        Vec3 eye = steve.getEyePosition(1.0F);
        Vec3 to = Vec3.atCenterOf(target);

        BlockHitResult hit = level.clip(new ClipContext(eye, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, steve));

        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        // Hit the target itself, or something at/behind it -> visible
        if (hit.getBlockPos().equals(target)) {
            return true;
        }
        return eye.distanceToSqr(hit.getLocation()) >= eye.distanceToSqr(to) - 0.5;
    }

    /**
     * Returns the cached (or freshly scanned) map of visible interesting blocks.
     */
    private static Map<Block, List<BlockPos>> scan(SteveEntity steve) {
        long tick = steve.level().getGameTime();
        int ttl = SteveConfig.WORLD_SCAN_CACHE_TICKS.get();

        ScanCache cached = CACHE.get(steve);
        if (cached != null && tick - cached.cachedAtTick < ttl) {
            return cached.visible();
        }

        Map<Block, List<BlockPos>> visible = scanWorld(steve);
        CACHE.put(steve, new ScanCache(tick, visible));
        return visible;
    }

    private static Map<Block, List<BlockPos>> scanWorld(SteveEntity steve) {
        Level level = steve.level();
        BlockPos center = steve.blockPosition();
        int radius = SteveConfig.WORLD_SCAN_RADIUS.get();
        int step = Math.max(1, SteveConfig.WORLD_SCAN_STEP.get());

        // Phase 1: cheap grid pass - collect candidate positions of interesting blocks.
        // No raycasts yet: getBlockState is fast, level.clip is not.
        Map<Block, List<BlockPos>> candidates = new HashMap<>();

        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dy = -radius; dy <= radius; dy += step) {
                for (int dz = -radius; dz <= radius; dz += step) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Block block = level.getBlockState(pos).getBlock();
                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
                        continue;
                    }
                    if (!INTERESTING.contains(block)) {
                        continue;
                    }
                    candidates.computeIfAbsent(block, k -> new ArrayList<>()).add(pos.immutable());
                }
            }
        }

        // Phase 2: line-of-sight check only for the candidates (usually a handful).
        // Cap the work per block type at the nearest 64 candidates to keep the
        // server tick fast even in dense forests.
        Map<Block, List<BlockPos>> visible = new HashMap<>();
        for (Map.Entry<Block, List<BlockPos>> entry : candidates.entrySet()) {
            Block block = entry.getKey();
            List<BlockPos> positions = entry.getValue();

            positions.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
            int checked = 0;
            for (BlockPos pos : positions) {
                if (checked >= 64) {
                    break;
                }
                checked++;
                if (hasLineOfSight(steve, pos)) {
                    visible.computeIfAbsent(block, k -> new ArrayList<>()).add(pos);
                }
            }
        }
        return visible;
    }
}
