package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.VisionScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resource gathering by ROUTING, not tunnel-digging.
 *
 * <p>The Steve walks a spiral of look-out stations around the start point
 * (amphibious navigation: walks on land, swims across water - never flies),
 * scanning with vision at each station ({@link VisionScanner#findVisible})
 * and mining ONLY visible target blocks. No tunnels are ever dug.</p>
 *
 * <p><b>Whole-tree felling:</b> when a mined log has a log above it, the Steve
 * enters fell mode - it collects the whole connected log component (BFS) and
 * climbs the trunk on a nerd-pole of REAL blocks from its inventory, felling
 * every log (jungle 2x2s and modded giants included), then dismantles the
 * pillar on the way down. No landscape litter.</p>
 *
 * <p>Stops early when: enough blocks gathered, inventory full, search timed
 * out, the route is exhausted, or felling stalls (no progress for a while).</p>
 */
public class GatherResourceAction extends BaseAction {

    private static final double ARRIVED_DISTANCE_SQ = 3.0 * 3.0;
    private static final double MINE_REACH_SQ = 5.0 * 5.0;
    private static final int ROUTE_STALL_TICKS = 60; // give navigation time to build a path
    private static final int MINE_STALL_TICKS = 60; // unreachable visible ore grace period

    private static final int FELL_MAX_HEIGHT = 64; // world height - pillar can reach any tree top
    private static final int FELL_STALL_TICKS = 60; // no progress -> give up
    private static final int FELL_MAX_LOGS = 200; // connected logs per tree (forest guard)
    private static final int FELL_WAIT_TICKS = 25; // vacuum pickup grace period for pillar material
    private static final int UNREACHABLE_TARGETS_LIMIT = 32;
    private static final int NEARBY_SCAN_RADIUS = 10; // cube scan around the bot (no line of sight)
    private static final int STATUS_INTERVAL = 40; // ticks between STATUS debug pings (20s @ 2TPS)
    private static final double PROGRESS_MOVE_DISTANCE_SQ = 8.0 * 8.0; // moving this far = progress
    private static final int WATER_FISH_OUT_TICKS = 120; // 6s in water with no path -> teleport out
    private static final Block[] PILLAR_MATERIALS = {
        net.minecraft.world.level.block.Blocks.GRASS_BLOCK, // everywhere underfoot, drops dirt
        net.minecraft.world.level.block.Blocks.DIRT,
        net.minecraft.world.level.block.Blocks.STONE,
        net.minecraft.world.level.block.Blocks.COBBLESTONE,
        net.minecraft.world.level.block.Blocks.GRAVEL,
        net.minecraft.world.level.block.Blocks.SAND
    };

    private Block targetBlock;
    /** The original requested resource (never overwritten by pillar material runs). */
    private Block resourceBlock;
    private int targetQuantity;
    private int gatheredCount;
    private boolean fillMode;
    private boolean anyLogMode;
    /** Resource count in the inventory at action start - quota is a delta over this. */
    private int startResourceCount;
    private int lastProgressCount;
    private long lastProgressTick;
    /** Position of the last progress event - moving away from it also resets the timeout. */
    private BlockPos lastProgressPos;
    private int expandDir;
    private BlockPos origin;
    private ResourceSearchPlanner.SearchState searchState;
    private BlockPos routeTarget;
    private BlockPos mineTarget;
    private int ticksOnRoute;
    private int waterStuckTicks;
    private int ticksOnMine;
    private int ticksRunning;
    private int statusCooldown;

    /** Visible-but-unreachable targets: skip them instead of looping forever. */
    private final Set<BlockPos> unreachableTargets = new HashSet<>();

    // Fell mode state
    private boolean fellMode;
    private boolean fellGatheringMaterial;
    private Block fellLogBlock;
    private final List<BlockPos> fellLogs = new ArrayList<>();
    private int fellHeight;
    private int fellStallTicks;
    private int fellWaitTicks;

    private enum Phase { SEARCH, ROUTING, MINING, FELL_ASCEND, FELL_DESCEND, FELL_GATHER, FELL_WAIT, FINISHED }

    private Phase phase = Phase.SEARCH;

    public GatherResourceAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("resource");
        if (blockName == null || blockName.isBlank()) {
            blockName = task.getStringParameter("block");
        }
        targetQuantity = task.getIntParameter("quantity", 16);
        fillMode = "true".equalsIgnoreCase(String.valueOf(task.getParameters().getOrDefault("fill", "false")));

        // "Gather wood/tree" means ANY log (oak, birch, spruce...) - the LLM
        // may name a single type, but the user asked for wood in general.
        anyLogMode = ResourceBlocks.isWoodRequest(blockName);
        targetBlock = anyLogMode ? null : ResourceBlocks.parseBlock(blockName);
        if (!anyLogMode && targetBlock == null) {
            result = ActionResult.failure("Unknown resource: " + blockName);
            return;
        }
        resourceBlock = targetBlock;

        gatheredCount = 0;
        // Quota counts what actually reaches the inventory (pickup fact),
        // as a delta over what was already there ("mine 50 MORE logs").
        startResourceCount = countResource();
        ticksRunning = 0;
        ticksOnRoute = 0;
        ticksOnMine = 0;
        fellMode = false;
        fellGatheringMaterial = false;
        fellHeight = 0;
        fellStallTicks = 0;
        fellLogs.clear();
        unreachableTargets.clear();
        origin = steve.blockPosition();
        searchState = new ResourceSearchPlanner.SearchState(origin, 0, 0, steve.level().getGameTime());

        // Ground movement only - never fly while gathering
        steve.setFlying(false);
        steve.getNavigation().stop();
        lastProgressTick = steve.level().getGameTime();
        lastProgressPos = steve.blockPosition();

        debugLog("GATHER", "search " + resourceLabel() + " x" + targetQuantity
            + " from " + origin);
    }

    @Override
    protected void onTick() {
        if (phase == Phase.FINISHED) {
            return;
        }
        ticksRunning++;

        // The quota counts what actually reached the INVENTORY (pickup
        // fact), not what was broken: drops lost in water/lava and logs
        // currently spent on a pillar must not inflate the count.
        gatheredCount = Math.max(0, countResource() - startResourceCount);

        // Search timeout only counts from the last PROGRESS: a bot that
        // keeps mining trees must never be killed by "Search timed out" -
        // the clock resets on every gathered log. Long walks/swims between
        // trees and stations (40-50s across a swamp, no chop) are progress
        // too: only a truly idle bot times out.
        long now = steve.level().getGameTime();
        if (gatheredCount != lastProgressCount) {
            lastProgressCount = gatheredCount;
            lastProgressTick = now;
            lastProgressPos = steve.blockPosition();
        } else if (lastProgressPos != null
                && steve.blockPosition().distSqr(lastProgressPos) >= PROGRESS_MOVE_DISTANCE_SQ) {
            lastProgressTick = now;
            lastProgressPos = steve.blockPosition();
        }
        if (now - lastProgressTick >= SteveConfig.GATHER_SEARCH_TIMEOUT.get()) {
            finish(false, "Search timed out - found " + gatheredCount + " " + resourceLabel());
            return;
        }

        if (fillMode) {
            // Fill mode: keep mining while there is any room left for the
            // requested resource (empty slot or a partially filled stack).
            // In any-log mode any free slot counts (mixed log types).
            boolean hasRoom = anyLogMode
                ? steve.getInventory().hasFreeSpace()
                : steve.getInventory().hasSpaceFor(currentTargetItem());
            if (!hasRoom) {
                finish(true, "Inventory full - gathered " + gatheredCount + " " + resourceLabel());
                return;
            }
        } else if (!steve.getInventory().hasFreeSpace()) {
            finish(true, "Inventory full");
            return;
        }

        // Completion is measured by blocks actually mined this session
        // (gatheredCount), NOT by the whole inventory: if the bot already had
        // 30 oak logs and the player asks for 50, exactly 50 more are mined
        // (80 total) - comparing the inventory would stop at 20 (bug).
        if (gatheredCount >= targetQuantity) {
            finish(true, "Gathered " + gatheredCount + " " + resourceLabel());
            return;
        }

        switch (phase) {
            case SEARCH -> phaseSearch();
            case ROUTING -> phaseRouting();
            case MINING -> phaseMining();
            case FELL_ASCEND -> phaseFellAscend();
            case FELL_DESCEND -> phaseFellDescend();
            case FELL_GATHER -> phaseFellGatherMaterial();
            case FELL_WAIT -> phaseFellWaitPickup();
            default -> { }
        }

        // Periodic STATUS ping so /steve debug shows what the bot is doing
        // even in silently-looped phases (stuck in water, circling a tree).
        if (--statusCooldown <= 0) {
            statusCooldown = STATUS_INTERVAL;
            BlockPos p = routeTarget;
            debugLog("STATUS",
                "phase=" + phase
                + " pos=" + steve.blockPosition()
                + " route=" + (p != null ? p : "-")
                + " dist=" + (p != null ? Math.round(Math.sqrt(horizontalDistanceSqr(p))) + "b" : "-")
                + " " + (steve.isInWater() ? "WATER " : "")
                + " nav=" + (steve.getNavigation().isInProgress() ? "moving" : "stopped")
                + " " + gatheredCount + "/" + targetQuantity);
        }
    }

    /** The item we are actually counting: logs while felling, else the target block. */
    private net.minecraft.world.item.Item currentTargetItem() {
        if (fellMode && fellLogBlock != null) {
            return fellLogBlock.asItem();
        }
        if (anyLogMode || targetBlock == null) {
            return net.minecraft.world.item.Items.OAK_LOG;
        }
        return targetBlock.asItem();
    }

    /** Human-readable resource name ("Oak Log" or "Wood" in any-log mode). */
    private String resourceLabel() {
        if (anyLogMode) {
            return "Wood";
        }
        return targetBlock != null ? targetBlock.getName().getString() : "?";
    }

    // ---- phases ----

    private void phaseSearch() {
        if (fellGatheringMaterial) {
            // A material run gone sideways must never look for the resource
            // itself (targetBlock is temporarily a material like dirt).
            phase = Phase.FELL_GATHER;
            return;
        }
        // Nearest material wins. Merge ray-visible blocks with the
        // no-line-of-sight nearby scan and pick the PHYSICALLY closest
        // candidate - a tree hidden behind a canopy 3 blocks away must win
        // over a visible one 30 blocks away (the bot used to skip nearby
        // trees and walk off into the distance).
        List<BlockPos> visible = anyLogMode
            ? VisionScanner.findVisibleAnyLog(steve)
            : VisionScanner.findVisible(steve, targetBlock);
        boolean logTarget = anyLogMode
            || (targetBlock != null && targetBlock.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS));

        List<BlockPos> nearby = VisionScanner.findNearbyBlocks(steve, NEARBY_SCAN_RADIUS, targetBlock);
        if (logTarget) {
            // lone logs of player buildings are not trees
            nearby = nearby.stream().filter(this::isTreeLog).toList();
        }

        List<BlockPos> all = new java.util.ArrayList<>(visible.size() + nearby.size());
        all.addAll(visible);
        all.addAll(nearby);
        BlockPos center = steve.blockPosition();
        BlockPos mine = all.stream()
            .filter(p -> !unreachableTargets.contains(p))
            .filter(p -> !isUnderwaterTarget(p)) // swamp: never dive for a log (drop loss, air)
            .min(Comparator.comparingDouble(p -> steve.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
            .orElse(null);
        if (mine != null) {
            if (!visible.contains(mine)) {
                debugLog("SEARCH", "nearby target at " + mine + " (behind foliage)");
            }
            mineTarget = mine;
            routeTarget = mine;
            phase = Phase.ROUTING;
            return;
        }

        // A target existed but all were unreachable by land (swamp islands):
        // blacklist them so we do not re-pick and re-walk into the water.
        for (BlockPos p : all) {
            if (!unreachableTargets.contains(p) && isUnderwaterTarget(p)) {
                unreachableTargets.add(p);
            }
        }
        unreachableTargets.retainAll(all); // keep the set small

        // No target anywhere: advance the route
        if (!ResourceSearchPlanner.hasNext(searchState, SteveConfig.GATHER_SEARCH_RADIUS.get(),
                SteveConfig.GATHER_RING_SPACING.get())) {
            // Origin rings exhausted: keep searching outward - walk away from
            // spawn in a compass sweep, widening each full turn, instead of
            // giving up ("Nothing found") or standing still forever.
            expandDir++;
            BlockPos station = expandStation();
            debugLog("SEARCH", "no targets locally, expanding outward to " + station);
            routeTarget = station;
            phase = Phase.ROUTING;
            return;
        }

        BlockPos station = ResourceSearchPlanner.stationFor(searchState,
            SteveConfig.GATHER_RING_SPACING.get(), SteveConfig.GATHER_STATIONS_PER_RING.get());
        searchState = ResourceSearchPlanner.next(searchState, SteveConfig.GATHER_STATIONS_PER_RING.get());
        debugLog("SEARCH", "no target visible, next station " + station);

        routeTarget = station;
        phase = Phase.ROUTING;
    }

    private void phaseRouting() {
        steve.setFlying(false); // ground movement, always
        ticksOnRoute++;

        // Water is no longer an obstacle: AmphibiousPathNavigation swims
        // across ponds to targets/stations on its own. Remaining
        // emergencies only: head under water (air is limited - bob up) or
        // stuck in water with no active path for a long time (e.g. under an
        // overhang the navigator cannot escape) - then fish out to shore.
        if (steve.isInWater()) {
            if (steve.isUnderWater()) {
                steve.setDeltaMovement(steve.getDeltaMovement().add(0, 0.15, 0));
            }
            if (steve.getNavigation().isInProgress()) {
                waterStuckTicks = 0; // swimming along a real route - fine
            } else if (++waterStuckTicks > WATER_FISH_OUT_TICKS) {
                BlockPos dry = findDrySpot(steve.blockPosition(), 8);
                if (dry == null) {
                    dry = findDrySpot(steve.blockPosition(), 16);
                }
                if (dry != null) {
                    int sy = steve.level().getHeightmapPos(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        dry).getY();
                    steve.teleportTo(dry.getX() + 0.5, sy + 1, dry.getZ() + 0.5);
                    steve.getNavigation().stop();
                    debugLog("ROUTING", "fished out of water to " + dry);
                }
                waterStuckTicks = 0;
            }
        } else {
            waterStuckTicks = 0;
        }

        // Stations sit at origin.y + 5 (look-out altitude) but Steve walks on
        // the ground: arrival and stall checks use the HORIZONTAL distance.
        boolean reached = horizontalDistanceSqr(routeTarget) <= ARRIVED_DISTANCE_SQ;

        if (reached) {
            ticksOnRoute = 0;
            if (routeTarget.equals(mineTarget)) {
                phase = Phase.MINING; // we arrived at the resource block
            } else {
                phase = Phase.SEARCH; // arrived at station: scan again
            }
            return;
        }

        if (!steve.getNavigation().isInProgress()) {
            // Target a dry, standable cell NEAR the route point: routeTarget
            // is a log/station block whose XZ may sit in a swamp pond.
            BlockPos land = findDrySpotNear(routeTarget, 4);
            if (land == null) {
                // All water around: with amphibious navigation the bot
                // simply SWIMS there - aim for a water surface cell.
                land = findSwimSpotNear(routeTarget, 4);
            }
            if (land == null) {
                // Neither dry land nor swimmable water anywhere near the
                // route point - nothing to path to.
                debugLog("ROUTING", "target has no walkable land or water, next");
                ticksOnRoute = 0;
                phase = fellGatheringMaterial ? Phase.FELL_GATHER : Phase.SEARCH;
                return;
            }
            steve.getNavigation().moveTo(land.getX() + 0.5, land.getY(), land.getZ() + 0.5, 1.0);
        }

        // Path cannot be built / blocked: skip this target after a grace
        // period. Remember it as unreachable so the next scan does not pick
        // the SAME block again (infinite silent loop: reachable-block ->
        // stall 60 ticks -> same block -> ...).
        if (ticksOnRoute > ROUTE_STALL_TICKS
                && steve.getNavigation().isDone()
                && horizontalDistanceSqr(routeTarget) > ARRIVED_DISTANCE_SQ) {
            ticksOnRoute = 0;
            steve.getNavigation().stop();
            if (mineTarget != null && routeTarget.equals(mineTarget)) {
                unreachableTargets.add(mineTarget);
                if (unreachableTargets.size() > UNREACHABLE_TARGETS_LIMIT) {
                    unreachableTargets.clear();
                }
                debugLog("ROUTING", "target unreachable, skipping " + mineTarget);
                mineTarget = null;
                if (fellGatheringMaterial) {
                    phase = Phase.FELL_GATHER; // re-pick another material block
                    return;
                }
            } else {
                debugLog("ROUTING", "station unreachable, next station");
            }
            phase = Phase.SEARCH; // next station / other candidate
        }
    }

    private void phaseMining() {
        if (mineTarget == null) {
            phase = Phase.SEARCH;
            return;
        }

        // Target block gone (already mined by someone else / dropped).
        // NOTE: in any-log mode targetBlock is null, so compare via
        // isLogBlockAt (LOGS tag) - block != null would always be true.
        if (!isLogBlockAt(mineTarget)) {
            mineTarget = null;
            ticksOnMine = 0;
            phase = Phase.SEARCH;
            return;
        }

        // Not close enough: walk to it
        if (steve.distanceToSqr(mineTarget.getX() + 0.5, mineTarget.getY() + 0.5, mineTarget.getZ() + 0.5) > MINE_REACH_SQ) {
            if (!steve.getNavigation().isInProgress()) {
                steve.getNavigation().moveTo(mineTarget.getX() + 0.5, mineTarget.getY(), mineTarget.getZ() + 0.5, 1.0);
            }
            // Visible but unreachable ore (cliff, lava): give up after a grace
            // period and remember the spot, instead of re-pathfinding forever.
            ticksOnMine++;
            if (ticksOnMine > MINE_STALL_TICKS && steve.getNavigation().isDone()) {
                ticksOnMine = 0;
                steve.getNavigation().stop();
                rememberUnreachable(mineTarget);
                mineTarget = null;
                phase = Phase.SEARCH;
            }
            return;
        }

        // In reach: break ONLY this block (no tunneling)
        steve.swing(InteractionHand.MAIN_HAND, true);
        if (!steve.level().destroyBlock(mineTarget, true)) {
            return; // failed to break - retry next tick
        }
        ticksOnMine = 0;

        if (fellGatheringMaterial) {
            // Gather enough pillar material: switch back to logs only once a
            // usable block is actually in the inventory. The drop needs a few
            // ticks to be vacuumed - wait instead of digging forever.
            targetBlock = fellLogBlock;
            fellGatheringMaterial = false;
            boolean havePillarBlock = !FellSupport.findSolidPillarBlock(steve.level(),
                steve.blockPosition(), steve.getInventory(), fellLogBlock).isEmpty();
            phase = havePillarBlock ? Phase.FELL_ASCEND : Phase.FELL_WAIT;
            return;
        }

        // No gatheredCount++ here: the quota counts the PICKUP fact
        // (inventory delta, updated in onTick), not the break fact.
        debugLog("MINE", resourceLabel() + " at " + mineTarget
            + " (" + gatheredCount + "/" + targetQuantity + ")");

        // Enter whole-tree felling: a log above the mined one means a tree
        // trunk - but only when leaves are nearby (player structures must
        // never be felled, even if built from logs)
        BlockPos above = mineTarget.above();
        mineTarget = null;
        if (!fellMode && isLogBlockAt(above)
                && isTreeLog(above)) {
            // NOTE: compare against targetBlock here - isTargetLog() uses
            // fellLogBlock which is only set inside enterFellMode().
            List<BlockPos> component = FellSupport.collectConnectedLogs(above,
                this::isLogBlockAt, FELL_MAX_LOGS);
            if (component.size() >= 2) { // trunk (or trunk+branches) = a tree, not a lone log
                enterFellMode(component);
                return;
            }
        }
        phase = Phase.SEARCH; // look for the next visible block
    }

    /** A log counts as a tree log when leaves are within 5 blocks. */
    private boolean isTreeLog(BlockPos pos) {
        return FellSupport.hasNearbyBlock(pos,
            p -> steve.level().getBlockState(p).getBlock() instanceof LeavesBlock, 5);
    }

    // ---- fell mode ----

    private void enterFellMode(List<BlockPos> component) {
        fellMode = true;
        // Keep only the logs ABOVE the water line: underwater trunk logs in
        // swamps would make the bot walk into the pond to chop them.
        List<BlockPos> aboveWater = component.stream()
            .filter(p -> !isUnderwaterTarget(p))
            .toList();
        if (aboveWater.isEmpty()) {
            return; // whole tree underwater - nothing to fell
        }
        // Concrete log type: the exact target, or the type of the first
        // connected log when in any-log (wood) mode.
        fellLogBlock = targetBlock != null
            ? targetBlock
            : steve.level().getBlockState(aboveWater.get(0)).getBlock();
        fellHeight = 0;
        fellStallTicks = 0;
        fellLogs.clear();
        fellLogs.addAll(aboveWater);
        debugLog("FELL", "whole-tree felling: " + aboveWater.size() + " logs (underwater: "
            + (component.size() - aboveWater.size()) + " skipped)");
        phase = Phase.FELL_ASCEND;
    }

    /** Nearest dry standable cell within the radius of center, or null. */
    private BlockPos findDrySpotNear(BlockPos center, int radius) {
        net.minecraft.world.level.Level lvl = steve.level();
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                int surfaceY = lvl.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(x, center.getY(), z)).getY();
                BlockPos stand = new BlockPos(x, surfaceY, z);
                // Surface itself must not be a log/leaves (a trunk column
                // would put the goal ON TOP of the tree) and must not be
                // water; the block below must be solid, not air/water.
                Block surface = lvl.getBlockState(stand).getBlock();
                if (surface.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS)
                        || surface instanceof net.minecraft.world.level.block.LeavesBlock
                        || lvl.getFluidState(stand).is(net.minecraft.tags.FluidTags.WATER)
                        || lvl.getFluidState(stand.below()).is(net.minecraft.tags.FluidTags.WATER)
                        || lvl.getBlockState(stand.below()).isAir()) {
                    continue;
                }
                int dist = Math.abs(dx) + Math.abs(dz);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = stand;
                }
            }
        }
        return best;
    }

    /** Nearest spot (block below not water) within the radius, or null. */
    private BlockPos findDrySpot(BlockPos center, int radius) {
        net.minecraft.world.level.Level lvl = steve.level();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos probe = new BlockPos(center.getX() + dx, center.getY(), center.getZ() + dz);
                if (!lvl.getFluidState(probe).is(net.minecraft.tags.FluidTags.WATER)
                        && !lvl.getFluidState(probe.below()).is(net.minecraft.tags.FluidTags.WATER)) {
                    double d = dx * dx + dz * dz;
                    if (d < bestDist) {
                        bestDist = d;
                        best = probe;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Nearest swimmable water cell within the radius of center, or null.
     * Used when there is no dry land around the route point: with amphibious
     * navigation the bot simply swims there. The returned position is the
     * top WATER block (the swimming node's height).
     */
    private BlockPos findSwimSpotNear(BlockPos center, int radius) {
        net.minecraft.world.level.Level lvl = steve.level();
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                // First free cell above the highest motion-blocking block or
                // fluid; for a pond that sits right above the water surface.
                int surfaceY = lvl.getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(x, center.getY(), z)).getY();
                BlockPos surface = new BlockPos(x, surfaceY, z);
                BlockPos water = lvl.getFluidState(surface).is(net.minecraft.tags.FluidTags.WATER)
                    ? surface
                    : (lvl.getFluidState(surface.below()).is(net.minecraft.tags.FluidTags.WATER)
                        ? surface.below() : null);
                if (water == null) {
                    continue;
                }
                int dist = Math.abs(dx) + Math.abs(dz);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = water;
                }
            }
        }
        return best;
    }

    /**
     * Total count of matching items currently in the inventory. Static for
     * unit tests (tag bindings are unavailable without a running server, so
     * the matcher is injected).
     */
    static int countResource(net.minecraft.world.Container inventory,
            java.util.function.Predicate<net.minecraft.world.item.Item> resourceMatcher) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && resourceMatcher.test(stack.getItem())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int countResource() {
        java.util.function.Predicate<net.minecraft.world.item.Item> matcher;
        if (anyLogMode) {
            // In-game only: item tag bindings require a running server.
            matcher = item -> item.builtInRegistryHolder().is(net.minecraft.tags.ItemTags.LOGS);
        } else if (resourceBlock != null) {
            net.minecraft.world.item.Item resourceItem = resourceBlock.asItem();
            matcher = item -> item == resourceItem;
        } else {
            matcher = item -> false;
        }
        return countResource(steve.getInventory(), matcher);
    }

    /**
     * Outward search station: compass sweep around the origin, widening by
     * 16 blocks per full turn. Used once the near rings are exhausted so the
     * bot keeps moving to find resources instead of giving up.
     */
    private BlockPos expandStation() {
        double angle = (expandDir % 8) * Math.PI / 4;
        int ring = expandDir / 8;
        int radius = SteveConfig.GATHER_SEARCH_RADIUS.get() + 16 * (ring + 1);
        int x = origin.getX() + (int) Math.round(radius * Math.cos(angle));
        int z = origin.getZ() + (int) Math.round(radius * Math.sin(angle));
        int y = steve.level().getHeightmapPos(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            new BlockPos(x, origin.getY(), z)).getY();
        return new BlockPos(x, y, z);
    }

    /** Whether the block at pos is the requested target, or ANY log in any-log mode. */
    private boolean isLogBlockAt(BlockPos pos) {
        Block block = steve.level().getBlockState(pos).getBlock();
        if (anyLogMode) {
            return block.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS);
        }
        return block == targetBlock;
    }

    /**
     * A log we would have to stand IN water to mine: either the block itself
     * is waterlogged, or the ground below it is water. Swamp trees drop their
     * lowest logs into the pond - skipping them keeps the bot dry.
     */
    private boolean isUnderwaterTarget(BlockPos pos) {
        net.minecraft.world.level.Level lvl = steve.level();
        return lvl.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)
            || lvl.getFluidState(pos.below()).is(net.minecraft.tags.FluidTags.WATER);
    }

    private void exitFellMode() {
        fellMode = false;
        fellGatheringMaterial = false;
        targetBlock = fellLogBlock;
        fellHeight = 0;
        fellStallTicks = 0;
        fellLogs.clear();
    }

    private boolean isTargetLog(BlockPos pos) {
        return steve.level().getBlockState(pos).getBlock() == fellLogBlock;
    }

    private void phaseFellAscend() {
        steve.setFlying(false);
        // Climbing is manual (setPos): an active navigation would drag the
        // Steve off the pillar back to the ground - stop it before ascending.
        if (steve.getNavigation().isInProgress()) {
            steve.getNavigation().stop();
        }

        // Stall guard: progress is a felled log OR a grown pillar
        fellStallTicks++;
        if (fellStallTicks > FELL_STALL_TICKS) {
            finish(false, "Stuck while felling (no progress for " + FELL_STALL_TICKS + " ticks)");
            return;
        }

        // 1. Fell any remaining log of the component within reach (branches!)
        BlockPos reachable = null;
        for (BlockPos log : fellLogs) {
            if (steve.distanceToSqr(log.getX() + 0.5, log.getY() + 0.5, log.getZ() + 0.5) <= MINE_REACH_SQ) {
                reachable = log;
                break;
            }
        }
        if (reachable != null) {
            steve.swing(InteractionHand.MAIN_HAND, true);
            if (steve.level().destroyBlock(reachable, true)) {
                gatheredCount++;
                fellLogs.remove(reachable);
                fellStallTicks = 0;
                debugLog("FELL", "felled " + fellLogBlock.getName().getString() + " at " + reachable
                    + " (" + gatheredCount + "/" + targetQuantity + ")");
            } else {
                // The log vanished (already broken elsewhere): stop retrying it
                fellLogs.remove(reachable);
                debugLog("FELL", "log at " + reachable + " already gone, skipping");
            }
            return;
        }

        // 2. Logs still above us? Climb the pillar (real block from inventory)
        int steveY = steve.blockPosition().getY();
        boolean logAbove = fellLogs.stream().anyMatch(p -> p.getY() > steveY);
        if (logAbove && fellHeight < FELL_MAX_HEIGHT) {
            BlockPos standPos = steve.blockPosition();
            ItemStack pillarBlock = FellSupport.findSolidPillarBlock(steve.level(), standPos,
                steve.getInventory(), fellLogBlock);
            if (pillarBlock.isEmpty()) {
                debugLog("FELL", "no pillar block in inventory - gathering material");
                phase = Phase.FELL_GATHER; // gather dirt/stone first
                return;
            }
            Block block = ((BlockItem) pillarBlock.getItem()).getBlock();
            BlockState standState = steve.level().getBlockState(standPos);
            // Leaves (and any other block in the way) are cleared first - the
            // canopy must not block the pillar. Drops are vacuumed.
            if (!standState.canBeReplaced()) {
                steve.swing(InteractionHand.MAIN_HAND, true);
                steve.level().destroyBlock(standPos, true);
                debugLog("FELL", "cleared " + standState.getBlock().getName().getString() + " at " + standPos);
                return; // retry next tick once the way is clear
            }
            steve.level().setBlock(standPos, block.defaultBlockState(), 3);
            steve.setPos(standPos.getX() + 0.5, standPos.getY() + 1, standPos.getZ() + 0.5);
            // Remove one block from the inventory slot that held it
            for (int i = 0; i < steve.getInventory().getContainerSize(); i++) {
                ItemStack slot = steve.getInventory().getItem(i);
                if (!slot.isEmpty() && slot.getItem() == pillarBlock.getItem()) {
                    steve.getInventory().removeItem(i, 1);
                    break;
                }
            }
            fellHeight++;
            fellStallTicks = 0;
            debugLog("FELL", "pillar up to y=" + (standPos.getY() + 1) + " (height " + fellHeight + ")");
            return;
        }

        // 3. No logs above (or height limit): dismantle the pillar on the way down
        phase = Phase.FELL_DESCEND;
    }

    private void phaseFellDescend() {
        if (steve.getNavigation().isInProgress()) {
            steve.getNavigation().stop();
        }
        fellStallTicks++;
        if (fellStallTicks > FELL_STALL_TICKS) {
            finish(false, "Stuck while dismantling the pillar");
            return;
        }

        BlockPos below = steve.blockPosition().below();
        BlockState belowState = steve.level().getBlockState(below);

        if (fellHeight > 0) {
            if (!belowState.isAir()
                    && belowState.getBlock() != fellLogBlock
                    && belowState.isCollisionShapeFullBlock(steve.level(), below)) {
                // Dismantle our own pillar block: drop returns to inventory via vacuum
                steve.swing(InteractionHand.MAIN_HAND, true);
                if (steve.level().destroyBlock(below, true)) {
                    steve.setPos(below.getX() + 0.5, below.getY(), below.getZ() + 0.5);
                    fellHeight--;
                    fellStallTicks = 0;
                }
                return;
            }
            if (belowState.isAir()) {
                // Pillar block was destroyed externally: just fall down a level
                steve.setPos(below.getX() + 0.5, below.getY(), below.getZ() + 0.5);
                fellHeight--;
                fellStallTicks = 0;
                return;
            }
            // Solid block below that is not our pillar (e.g. the log we stand
            // on after a branch fell): drop straight down onto it
            steve.setPos(below.getX() + 0.5, below.getY(), below.getZ() + 0.5);
            fellHeight--;
            fellStallTicks = 0;
            return;
        }

        // Back on the ground. If unreachable logs remain (branches far out),
        // try to walk to the nearest one once; the stall guard exits if not.
        if (!fellLogs.isEmpty()) {
            BlockPos nearest = fellLogs.stream()
                .min(Comparator.comparingDouble(p -> horizontalDistanceSqr(p)))
                .orElse(null);
            if (nearest != null) {
                routeTarget = nearest;
                phase = Phase.ROUTING;
                return;
            }
        }

        debugLog("FELL", "tree felled, pillar dismantled");
        exitFellMode();
        phase = Phase.SEARCH;
    }

    private void phaseFellGatherMaterial() {
        // Find a solid material to build the pillar from - but never
        // the block right under our feet (digging it would leave a hole)
        BlockPos feet = steve.blockPosition().below();
        for (Block material : PILLAR_MATERIALS) {
            List<BlockPos> visible = VisionScanner.findVisible(steve, material);
            BlockPos chosen = visible.stream()
                .filter(p -> !p.equals(feet) && !p.equals(steve.blockPosition()))
                .filter(p -> !unreachableTargets.contains(p))
                .min(Comparator.comparingDouble(p -> steve.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                .orElse(null);
            if (chosen != null) {
                mineTarget = chosen;
                routeTarget = chosen;
                targetBlock = material;
                fellGatheringMaterial = true;
                phase = Phase.ROUTING;
                return;
            }
        }

        // Nothing visible via ray scan (forest canopy, swamp reeds, water):
        // brute-force cube scan - grass/dirt is almost always right next to
        // the bot, it just cannot "see" it through the leaves.
        for (Block material : PILLAR_MATERIALS) {
            List<BlockPos> nearby = VisionScanner.findNearbyBlocks(steve, NEARBY_SCAN_RADIUS, material);
            BlockPos chosen = nearby.stream()
                .filter(p -> !p.equals(feet) && !p.equals(steve.blockPosition()))
                .filter(p -> !unreachableTargets.contains(p))
                .min(Comparator.comparingDouble(p -> steve.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                .orElse(null);
            if (chosen != null) {
                debugLog("FELL", "material found by nearby scan: " + material.getName().getString() + " at " + chosen);
                mineTarget = chosen;
                routeTarget = chosen;
                targetBlock = material;
                fellGatheringMaterial = true;
                phase = Phase.ROUTING;
                return;
            }
        }
        finish(false, "No blocks left to climb the tree with");
    }

    /**
     * After mining a material block the drop needs a few ticks to be vacuumed
     * into the inventory. Wait briefly instead of mining another block (which
     * previously looped forever when the drop never arrived).
     */
    private void phaseFellWaitPickup() {
        fellWaitTicks++;
        boolean havePillarBlock = !FellSupport.findSolidPillarBlock(steve.level(),
            steve.blockPosition(), steve.getInventory(), fellLogBlock).isEmpty();
        if (havePillarBlock) {
            fellWaitTicks = 0;
            phase = Phase.FELL_ASCEND;
            return;
        }
        if (fellWaitTicks > FELL_WAIT_TICKS) {
            fellWaitTicks = 0;
            phase = Phase.FELL_GATHER; // try mining another material block
        }
    }

    // ---- helpers ----

    private void finish(boolean success, String message) {
        phase = Phase.FINISHED;
        steve.getNavigation().stop();
        steve.setFlying(false);
        if (fellMode) {
            // Never leave the pillar standing (quota reached / full inventory /
            // stall mid-felling): dismantle it so the landscape stays clean
            dismantlePillar();
        }
        exitFellMode();
        result = success ? ActionResult.success(message) : ActionResult.failure(message);
    }

    /**
     * Removes the pillar blocks under the Steve, dropping down level by level.
     * Drops are picked up by the vacuum, so nothing is left in the landscape.
     */
    private void dismantlePillar() {
        int guard = 0;
        while (fellHeight > 0 && guard++ < FELL_MAX_HEIGHT) {
            BlockPos below = steve.blockPosition().below();
            BlockState state = steve.level().getBlockState(below);
            if (!state.isAir() && state.getBlock() != fellLogBlock) {
                steve.level().destroyBlock(below, true);
            }
            steve.setPos(below.getX() + 0.5, below.getY(), below.getZ() + 0.5);
            fellHeight--;
        }
        fellHeight = 0;
    }

    private void debugLog(String type, String message) {
        com.steve.ai.debug.AgentDebugBuffer.log(steve.getSteveName(), type, message);
    }

    /** Squared horizontal (XZ) distance to a block - for ground navigation checks. */
    private double horizontalDistanceSqr(BlockPos pos) {
        double dx = steve.getX() - (pos.getX() + 0.5);
        double dz = steve.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    private void rememberUnreachable(BlockPos pos) {
        if (unreachableTargets.size() >= UNREACHABLE_TARGETS_LIMIT) {
            unreachableTargets.clear(); // keep the set bounded
        }
        unreachableTargets.add(pos);
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        steve.setFlying(false);
        if (fellMode) {
            // Task cancelled mid-felling: dismantle the pillar before leaving
            dismantlePillar();
        }
    }

    @Override
    public String getDescription() {
        return "Gather " + targetQuantity + " " + resourceLabel()
            + " (" + gatheredCount + " found)";
    }
}
