package com.steve.ai.memory;

import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Honest vision for Steve: scans the world for interesting blocks (trees, ores,
 * chests) but only reports blocks with a clear line of sight (no x-ray, no cheats).
 *
 * <p>Scanning is <b>approximate sampling</b> beyond the precise near zone:
 * the near zone ({@value #PRECISE_RADIUS} blocks) is scanned block-by-block,
 * the far zone uses the configured grid step. The cache is per-Steve and is
 * invalidated when Steve moves, changes dimension, or the TTL expires.</p>
 *
 * <p>Performance safeguards: positions in unloaded chunks are skipped (no
 * synchronous chunk loading), and the effective grid step is automatically
 * increased so a single scan never exceeds {@value #MAX_SCAN_POSITIONS} block
 * lookups (prevents server tick stalls with large radius / small step).</p>
 */
public final class VisionScanner {

    /** Max block lookups per full scan (keeps server tick responsive). */
    private static final int MAX_SCAN_POSITIONS = 100_000;

    /** Radius (blocks) scanned block-by-block for exact results. */
    private static final int PRECISE_RADIUS = 16;

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

        // Ores (nether)
        INTERESTING.add(Blocks.NETHER_GOLD_ORE);
        INTERESTING.add(Blocks.NETHER_QUARTZ_ORE);
        INTERESTING.add(Blocks.ANCIENT_DEBRIS);

        // Storage
        INTERESTING.add(Blocks.CHEST);
        INTERESTING.add(Blocks.TRAPPED_CHEST);
        INTERESTING.add(Blocks.BARREL);
    }

    private record ScanCache(long cachedAtTick, BlockPos origin, ResourceKey<Level> dimension,
                             Map<Block, List<BlockPos>> visible) {}

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
     * Block names are registry ids (oak_log), matching what actions expect.
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
                parts.add(blockId(block) + " x" + positions.size()
                    + " (" + distance + "m " + direction + ")");
            });

        return String.join(", ", parts);
    }

    /**
     * Checks whether Steve has a clear line of sight to the given block.
     * Leaves are treated as transparent for the sight ray (a trunk hidden
     * behind the canopy is still a valid target) - everything else with a
     * collision blocks the view.
     */
    public static boolean hasLineOfSight(SteveEntity steve, BlockPos target) {
        Level level = steve.level();
        Vec3 eye = steve.getEyePosition(1.0F);
        Vec3 to = Vec3.atCenterOf(target);
        Vec3 dir = to.subtract(eye);
        if (dir.lengthSqr() < 1.0E-4) {
            return true; // target is inside/at the eye - trivially visible
        }
        dir = dir.normalize();

        // Step through leaves (they have a collision shape but should not
        // hide ores/logs behind the canopy); hard cap on iterations.
        for (int i = 0; i < 16; i++) {
            BlockHitResult hit = level.clip(new ClipContext(eye, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, steve));

            if (hit.getType() == HitResult.Type.MISS) {
                return true;
            }
            // Hit the target itself, or something at/behind it -> visible
            if (hit.getBlockPos().equals(target)) {
                return true;
            }
            if (eye.distanceToSqr(hit.getLocation()) >= eye.distanceToSqr(to) - 0.5) {
                return true;
            }
            BlockState hitState = level.getBlockState(hit.getBlockPos());
            if (hitState.getBlock() instanceof LeavesBlock) {
                // leaves are transparent: continue the ray just past them
                eye = hit.getLocation().add(dir.scale(0.2));
                continue;
            }
            return false;
        }
        return false;
    }

    /**
     * Drops the cached scan for a Steve (call when the entity is removed/despawned).
     */
    public static void forget(SteveEntity steve) {
        CACHE.remove(steve);
    }

    /**
     * Clears all cached scans (call on server shutdown).
     */
    public static void clearCache() {
        CACHE.clear();
    }

    /**
     * Returns the cached (or freshly scanned) map of visible interesting blocks.
     * Cache is reused only if Steve is still at the same position in the same
     * dimension and the TTL has not expired.
     */
    private static Map<Block, List<BlockPos>> scan(SteveEntity steve) {
        long tick = steve.level().getGameTime();
        BlockPos pos = steve.blockPosition();
        ResourceKey<Level> dim = steve.level().dimension();
        int ttl = SteveConfig.WORLD_SCAN_CACHE_TICKS.get();

        ScanCache cached = CACHE.get(steve);
        if (cached != null && tick - cached.cachedAtTick < ttl
                && cached.origin().equals(pos) && cached.dimension() == dim) {
            return cached.visible();
        }

        Map<Block, List<BlockPos>> visible = scanWorld(steve);
        CACHE.put(steve, new ScanCache(tick, pos.immutable(), dim, visible));
        return visible;
    }

    private static Map<Block, List<BlockPos>> scanWorld(SteveEntity steve) {
        Level level = steve.level();
        BlockPos center = steve.blockPosition();
        int radius = SteveConfig.WORLD_SCAN_RADIUS.get();
        int configuredStep = Math.max(1, SteveConfig.WORLD_SCAN_STEP.get());

        // Budget guard: auto-increase the effective step so a full scan stays
        // within MAX_SCAN_POSITIONS block lookups (no server tick stalls).
        int step = configuredStep;
        while (step <= 8) {
            long positions = (long) Math.pow((2L * radius / step) + 1, 3);
            if (positions <= MAX_SCAN_POSITIONS) {
                break;
            }
            step *= 2;
        }

        Map<Block, Set<BlockPos>> candidates = new HashMap<>();
        collectCandidates(level, center, radius, step, candidates);

        // Precise pass: block-by-block in the near zone so thin targets
        // (e.g. a single oak log trunk) are never missed close to Steve.
        // Set-based collection deduplicates positions that overlap between
        // the coarse and precise passes (no double raycasts, no inflated xN).
        if (step > 1) {
            int preciseRadius = Math.min(PRECISE_RADIUS, radius);
            collectCandidates(level, center, preciseRadius, 1, candidates);
        }

        // Line-of-sight check only for the candidates (usually a handful).
        // Cap the work per block type at the nearest 64 candidates to keep the
        // server tick fast even in dense forests.
        Map<Block, List<BlockPos>> visible = new HashMap<>();
        for (Map.Entry<Block, Set<BlockPos>> entry : candidates.entrySet()) {
            Block block = entry.getKey();
            List<BlockPos> positions = new ArrayList<>(entry.getValue());

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

    private static void collectCandidates(Level level, BlockPos center, int radius, int step,
                                          Map<Block, Set<BlockPos>> candidates) {
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dy = -radius; dy <= radius; dy += step) {
                for (int dz = -radius; dz <= radius; dz += step) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    // Never load chunks synchronously on the server tick
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    Block block = level.getBlockState(pos).getBlock();
                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
                        continue;
                    }
                    if (!INTERESTING.contains(block)) {
                        continue;
                    }
                    candidates.computeIfAbsent(block, k -> new LinkedHashSet<>()).add(pos.immutable());
                }
            }
        }
    }

    private static String blockId(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key != null ? key.getPath() : block.toString();
    }
}
