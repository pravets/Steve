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
    private static final int NEARBY_SCAN_RADIUS = 10; // no-line-of-sight fallback scan
    private static final int NEARBY_SCAN_INTERVAL = 20; // ticks between cube scans
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
    private int nearbyScanCooldown;
    private BlockPos origin;
    private ResourceSearchPlanner.SearchState searchState;
    private BlockPos routeTarget;
    private BlockPos mineTarget;
    private int ticksOnRoute;
    private int ticksOnMine;
    private int ticksRunning;

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

        debugLog("GATHER", "search " + resourceLabel() + " x" + targetQuantity
            + " from " + origin);
    }

    @Override
    protected void onTick() {
        if (phase == Phase.FINISHED) {
            return;
        }
        ticksRunning++;

        if (ResourceSearchPlanner.isTimedOut(searchState, steve.level().getGameTime(),
                SteveConfig.GATHER_SEARCH_TIMEOUT.get())) {
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
        List<BlockPos> visible = anyLogMode
            ? VisionScanner.findVisibleAnyLog(steve)
            : VisionScanner.findVisible(steve, targetBlock);
        if (!visible.isEmpty()) {
            // Nearest visible target wins - skip known-unreachable positions
            // (cliff/lava ores would otherwise be re-picked forever). The
            // isTreeLog filter (leaves nearby) applies ONLY to logs, so ores,
            // stone etc. are never rejected for lacking leaves.
            boolean logTarget = anyLogMode
                || targetBlock.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS);
            List<BlockPos> candidates = visible.stream()
                .filter(p -> !unreachableTargets.contains(p))
                .filter(p -> !logTarget || isTreeLog(p))
                .toList();
            if (visible.size() != candidates.size()) {
                debugLog("SEARCH", "visible=" + visible.size() + ", candidates=" + candidates.size());
            }
            mineTarget = candidates.stream()
                .min(Comparator.comparingDouble(p -> steve.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                .orElse(null);
            if (mineTarget != null) {
                routeTarget = mineTarget;
                phase = Phase.ROUTING;
                return;
            }
        }

        // No target visible via ray scan: look around without line-of-sight
        // (forest canopies block the view ray) - walk to the nearest log we
        // find within NEARBY_SCAN_RADIUS. Cooldown keeps the cube scan cheap.
        if (nearbyScanCooldown-- <= 0) {
            nearbyScanCooldown = NEARBY_SCAN_INTERVAL;
            List<BlockPos> nearby = VisionScanner.findNearbyBlocks(steve, NEARBY_SCAN_RADIUS, targetBlock);
            if (!nearby.isEmpty()) {
                BlockPos nearest = nearby.stream()
                    .filter(p -> !unreachableTargets.contains(p))
                    .min(Comparator.comparingDouble(p -> steve.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                    .orElse(null);
                if (nearest != null) {
                    debugLog("SEARCH", "nearby scan found log at " + nearest + " (no line of sight)");
                    mineTarget = nearest;
                    routeTarget = nearest;
                    phase = Phase.ROUTING;
                    return;
                }
            }
        }

        // No target visible: advance the route
        if (!ResourceSearchPlanner.hasNext(searchState, SteveConfig.GATHER_SEARCH_RADIUS.get(),
                SteveConfig.GATHER_RING_SPACING.get())) {
            finish(false, "Nothing found within " + SteveConfig.GATHER_SEARCH_RADIUS.get() + " blocks");
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
            steve.getNavigation().moveTo(routeTarget.getX() + 0.5, routeTarget.getY(), routeTarget.getZ() + 0.5, 1.0);
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

        // Target block gone (already mined by someone else / dropped)
        if (steve.level().getBlockState(mineTarget).getBlock() != targetBlock) {
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
        // Concrete log type: the exact target, or the type of the first
        // connected log when in any-log (wood) mode.
        fellLogBlock = targetBlock != null
            ? targetBlock
            : steve.level().getBlockState(component.get(0)).getBlock();
        fellHeight = 0;
        fellStallTicks = 0;
        fellLogs.clear();
        fellLogs.addAll(component);
        debugLog("FELL", "whole-tree felling: " + component.size() + " logs");
        phase = Phase.FELL_ASCEND;
    }

    /** Whether the block at pos is the requested target, or ANY log in any-log mode. */
    private boolean isLogBlockAt(BlockPos pos) {
        Block block = steve.level().getBlockState(pos).getBlock();
        if (anyLogMode) {
            return block.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS);
        }
        return block == targetBlock;
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
