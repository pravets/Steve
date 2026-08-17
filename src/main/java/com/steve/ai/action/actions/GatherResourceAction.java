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
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resource gathering by ROUTING, not tunnel-digging.
 *
 * <p>The Steve walks (ground navigation, never flying) a spiral of look-out
 * stations around the start point, scanning with vision at each station
 * ({@link VisionScanner#findVisible}) and mining ONLY visible target blocks.
 * No tunnels are ever dug.</p>
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
    private static final int REACH_SEARCH_RANGE = 20; // blood-style dry-land reachability radius
    private static final int STATUS_INTERVAL = 40; // ticks between STATUS debug pings (20s @ 2TPS)
    private static final Block[] PILLAR_MATERIALS = {
        net.minecraft.world.level.block.Blocks.GRASS_BLOCK, // everywhere underfoot, drops dirt
        net.minecraft.world.level.block.Blocks.DIRT,
        net.minecraft.world.level.block.Blocks.STONE,
        net.minecraft.world.level.block.Blocks.COBBLESTONE,
        net.minecraft.world.level.block.Blocks.GRAVEL,
        net.minecraft.world.level.block.Blocks.SAND
    };

    private Block targetBlock;
    private int targetQuantity;
    private int gatheredCount;
    private boolean fillMode;
    private boolean anyLogMode;
    private int lastProgressCount;
    private long lastProgressTick;
    private Set<BlockPos> reachableCache;
    private long reachableCacheTick;
    private int expandDir;
    private BlockPos origin;
    private ResourceSearchPlanner.SearchState searchState;
    private BlockPos routeTarget;
    private BlockPos mineTarget;
    private int ticksOnRoute;
    private int waterStuckTicks;
    private int waterHopTicks;
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

        gatheredCount = 0;
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

        debugLog("GATHER", "search " + resourceLabel() + " x" + targetQuantity
            + " from " + origin);
    }

    @Override
    protected void onTick() {
        if (phase == Phase.FINISHED) {
            return;
        }
        ticksRunning++;

        // Search timeout only counts from the last PROGRESS: a bot that
        // keeps mining trees must never be killed by "Search timed out" -
        // the clock resets on every felled log.
        long now = steve.level().getGameTime();
        if (gatheredCount != lastProgressCount) {
            lastProgressCount = gatheredCount;
            lastProgressTick = now;
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
            .filter(p -> !isUnderwaterTarget(p)) // swamp: never walk into water for a log
            .filter(p -> hasReachableLandNear(p)) // skip island trees unreachable by land
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

        // Stuck / standing in a swamp pond. Ground pathfinding does not work
        // from inside water (the nav node is a water node it cannot leave),
        // so actively swim toward the nearest dry land: horizontal push +
        // constant upward bob overrides the water drag. If that still does
        // not get out within ~3s, teleport to the nearest dry spot - a
        // permanently stuck bot is worse than a 2-block hop.
        if (steve.isInWater()) {
            waterStuckTicks++;
            BlockPos dry = findDrySpot(steve.blockPosition(), 8);
            if (dry == null) {
                dry = findDrySpot(steve.blockPosition(), 16);
            }
            if (dry != null && waterStuckTicks < 20 * 3) {
                net.minecraft.world.phys.Vec3 move = steve.getDeltaMovement();
                double dx = dry.getX() + 0.5 - steve.getX();
                double dz = dry.getZ() + 0.5 - steve.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.5) {
                    move = move.add(dx / len * 0.3, 0, dz / len * 0.3);
                }
                move = move.add(0, 0.12, 0);
                steve.setDeltaMovement(move);
                steve.getNavigation().stop();
                if (waterStuckTicks % 20 == 0) {
                    debugLog("ROUTING", "swimming out of water toward " + dry);
                }
            } else if (dry != null) {
                // gave the swim a fair chance - fish him out
                int sy = steve.level().getHeightmapPos(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    dry).getY();
                steve.teleportTo(dry.getX() + 0.5, sy + 1, dry.getZ() + 0.5);
                steve.getNavigation().stop();
                waterStuckTicks = 0;
                debugLog("ROUTING", "fished out of water to " + dry);
            }
            return;
        }
        waterStuckTicks = 0;
        waterHopTicks = 0;

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
            // is a log/station block whose XZ may sit in a swamp pond - a
            // path to a point under water never builds (the bot then marks
            // every log of the tree unreachable and gives up).
            BlockPos land = findDrySpotNear(routeTarget, 4);
            if (land == null) {
                // No dry cell within 4 of the target: if it sits across a
                // swamp pond, try building a bridge - fill the water cell in
                // front (toward the target) with a carried dirt/stone/log
                // block, ONE step at a time, as long as we have the material.
                if (tryBridgeToward(routeTarget)) {
                    return; // block placed - path can advance next tick
                }
                // The route point (station/log) sits in water with no dry
                // cell within 4 - there is nothing to walk to. Skip it right
                // away instead of force-stalling: the previous `return` here
                // skipped the stall check below and looped forever.
                debugLog("ROUTING", "target has no walkable land, next");
                ticksOnRoute = 0;
                phase = Phase.SEARCH;
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

        gatheredCount++;
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
     * Tries to place one carried block (dirt/stone/log) into the water cell
     * just in front of the bot, in the direction of {@code target}, so the
     * ground path can advance one more step across a swamp pond. Only works
     * if there is usable block material in the inventory; the bridge is left
     * in place (no need to remove it afterwards). Returns true if a block was
     * placed.
     */
    private boolean tryBridgeToward(BlockPos target) {
        net.minecraft.world.level.Level lvl = steve.level();
        double dxs = target.getX() + 0.5 - steve.getX();
        double dzs = target.getZ() + 0.5 - steve.getZ();
        double len = Math.sqrt(dxs * dxs + dzs * dzs);
        if (len < 0.5) {
            return false;
        }
        double ux = dxs / len;
        double uz = dzs / len;
        int ax = (int) Math.floor(steve.getX() + 0.5 + ux * 1.4 - 0.5);
        int az = (int) Math.floor(steve.getZ() + 0.5 + uz * 1.4 - 0.5);

        // find the water surface level in that column (stopping above ground)
        BlockPos ahead = null;
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos probe = new BlockPos(ax, steve.blockPosition().getY() + dy, az);
            if (lvl.getFluidState(probe).is(net.minecraft.tags.FluidTags.WATER)
                    && lvl.getBlockState(probe).canBeReplaced()) {
                ahead = probe;
                break;
            }
        }
        if (ahead == null) {
            return false; // nothing to fill ahead
        }

        // need material; prefer non-resource (dirt/stone), logs as fallback
        ItemStack mat = FellSupport.findSolidPillarBlock(lvl, ahead, steve.getInventory(), targetBlock);
        if (mat.isEmpty()) {
            return false; // no blocks to build with
        }
        Block block = ((net.minecraft.world.item.BlockItem) mat.getItem()).getBlock();
        lvl.setBlock(ahead, block.defaultBlockState(), 3);
        for (int i = 0; i < steve.getInventory().getContainerSize(); i++) {
            ItemStack slot = steve.getInventory().getItem(i);
            if (!slot.isEmpty() && slot.getItem() == mat.getItem()) {
                steve.getInventory().removeItem(i, 1);
                break;
            }
        }
        steve.getNavigation().stop();
        debugLog("ROUTING", "bridged water at " + ahead + " (" + block.getName().getString() + ")");
        return true;
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

    /** Is (x, y, z) a dry walkable surface cell (not log/leaves/water, solid below). */
    private boolean isDrySurface(int x, int y, int z) {
        net.minecraft.world.level.Level lvl = steve.level();
        BlockPos stand = new BlockPos(x, y, z);
        Block surface = lvl.getBlockState(stand).getBlock();
        if (surface.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS)
                || surface instanceof net.minecraft.world.level.block.LeavesBlock) {
            return false;
        }
        if (lvl.getFluidState(stand).is(net.minecraft.tags.FluidTags.WATER)
                || lvl.getFluidState(stand.below()).is(net.minecraft.tags.FluidTags.WATER)
                || lvl.getBlockState(stand.below()).isAir()) {
            return false;
        }
        return true;
    }

    /**
     * BFS over dry walkable cells starting from the bot, up to {@code range}
     * blocks away in X/Z. Returns the set of dry surface positions reachable
     * over land. Used to reject island trees (a swamp pond around a log means
     * the ground path never reaches it).
     */
    private Set<BlockPos> reachableDry(int range) {
        int y = steve.blockPosition().getY();
        Set<BlockPos> reachable = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = steve.blockPosition();
        if (!isDrySurface(start.getX(), y, start.getZ())) {
            // bot currently not on a dry cell (e.g. in water): find the
            // nearest dry cell to seed the flood fill
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (isDrySurface(start.getX() + dx, y, start.getZ() + dz)) {
                        start = new BlockPos(start.getX() + dx, y, start.getZ() + dz);
                        dx = dz = 99;
                        break;
                    }
                }
            }
            if (!isDrySurface(start.getX(), y, start.getZ())) {
                return reachable; // fully in water - nothing reachable by land
            }
        }
        queue.add(start);
        reachable.add(start);
        Set<BlockPos> visited = new HashSet<>();
        visited.add(start);
        int[] dxs = {1, -1, 0, 0};
        int[] dzs = {0, 0, 1, -1};
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (int i = 0; i < 4; i++) {
                int nx = cur.getX() + dxs[i];
                int nz = cur.getZ() + dzs[i];
                if (Math.max(Math.abs(nx - start.getX()), Math.abs(nz - start.getZ())) > range) {
                    continue;
                }
                BlockPos next = new BlockPos(nx, y, nz);
                if (visited.contains(next)) {
                    continue;
                }
                visited.add(next);
                if (isDrySurface(nx, y, nz)) {
                    reachable.add(next);
                    queue.add(next);
                }
            }
        }
        return reachable;
    }

    /** Whether a dry land cell lies within {@code near} blocks (X/Z) of the
     * target. Swamp trees on islands have no land adjacent within reach, so
     * they are skipped instead of running into the pond forever. The BFS is
     * cached for this tick (several candidates ask in the same iteration).
     */
    private boolean hasReachableLandNear(BlockPos target) {
        long now = steve.level().getGameTime();
        if (reachableCache == null || now != reachableCacheTick) {
            reachableCache = reachableDry(REACH_SEARCH_RANGE);
            reachableCacheTick = now;
        }
        if (reachableCache == null || reachableCache.isEmpty()) {
            return false; // bot is in the middle of a pond - nothing by land
        }
        for (BlockPos land : reachableCache) {
            if (Math.abs(land.getX() - target.getX()) <= 2
                    && Math.abs(land.getZ() - target.getZ()) <= 2) {
                return true;
            }
        }
        return false;
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
