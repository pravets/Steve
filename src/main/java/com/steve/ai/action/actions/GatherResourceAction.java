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
    private static final int UNREACHABLE_TARGETS_LIMIT = 32;
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

    private enum Phase { SEARCH, ROUTING, MINING, FELL_ASCEND, FELL_DESCEND, FELL_GATHER, FINISHED }

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

        targetBlock = ResourceBlocks.parseBlock(blockName);
        if (targetBlock == null) {
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

        debugLog("GATHER", "search " + targetBlock.getName().getString() + " x" + targetQuantity
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
            finish(false, "Search timed out - found " + gatheredCount + " " + targetBlock.getName().getString());
            return;
        }

        if (!steve.getInventory().hasFreeSpace()) {
            // Auto-return is handled by the planner in the next Stage-3 commit
            finish(true, "Inventory full");
            return;
        }

        // Completion is measured by what actually reached the inventory
        // (drop -> vacuum pickup), not by how many blocks were broken.
        if (steve.getInventory().countItem(currentTargetItem()) >= targetQuantity) {
            finish(true, "Gathered " + gatheredCount + " " + targetBlock.getName().getString());
            return;
        }

        switch (phase) {
            case SEARCH -> phaseSearch();
            case ROUTING -> phaseRouting();
            case MINING -> phaseMining();
            case FELL_ASCEND -> phaseFellAscend();
            case FELL_DESCEND -> phaseFellDescend();
            case FELL_GATHER -> phaseFellGatherMaterial();
            default -> { }
        }
    }

    /** The item we are actually counting: logs while felling, else the target block. */
    private net.minecraft.world.item.Item currentTargetItem() {
        return fellMode ? fellLogBlock.asItem() : targetBlock.asItem();
    }

    // ---- phases ----

    private void phaseSearch() {
        List<BlockPos> visible = VisionScanner.findVisible(steve, targetBlock);
        if (!visible.isEmpty()) {
            // Nearest visible target wins - skip known-unreachable positions
            // (cliff/lava ores would otherwise be re-picked forever) and logs
            // that are NOT part of a tree (player structures stay untouched)
            mineTarget = visible.stream()
                .filter(p -> !unreachableTargets.contains(p))
                .filter(this::isTreeLog)
                .min(Comparator.comparingDouble(p -> steve.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                .orElse(null);
            if (mineTarget != null) {
                routeTarget = mineTarget;
                phase = Phase.ROUTING;
                return;
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

        // Path cannot be built / blocked: skip this station after a grace period
        if (ticksOnRoute > ROUTE_STALL_TICKS
                && steve.getNavigation().isDone()
                && horizontalDistanceSqr(routeTarget) > ARRIVED_DISTANCE_SQ) {
            ticksOnRoute = 0;
            steve.getNavigation().stop();
            phase = Phase.SEARCH; // next station
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
            // usable block is actually in the inventory (grass drops dirt)
            targetBlock = fellLogBlock;
            fellGatheringMaterial = false;
            boolean havePillarBlock = !FellSupport.findSolidPillarBlock(steve.level(),
                steve.blockPosition(), steve.getInventory(), fellLogBlock).isEmpty();
            phase = havePillarBlock ? Phase.FELL_ASCEND : Phase.FELL_GATHER;
            return;
        }

        gatheredCount++;
        debugLog("MINE", targetBlock.getName().getString() + " at " + mineTarget
            + " (" + gatheredCount + "/" + targetQuantity + ")");

        // Enter whole-tree felling: a log above the mined one means a tree
        // trunk - but only when leaves are nearby (player structures must
        // never be felled, even if built from logs)
        BlockPos above = mineTarget.above();
        mineTarget = null;
        if (!fellMode && steve.level().getBlockState(above).getBlock() == targetBlock
                && isTreeLog(above)) {
            // NOTE: compare against targetBlock here - isTargetLog() uses
            // fellLogBlock which is only set inside enterFellMode().
            List<BlockPos> component = FellSupport.collectConnectedLogs(above,
                p -> steve.level().getBlockState(p).getBlock() == targetBlock, FELL_MAX_LOGS);
            if (component.size() >= 2) { // trunk (or trunk+branches) = a tree, not a lone log
                enterFellMode(component);
                return;
            }
        }
        phase = Phase.SEARCH; // look for the next visible block
    }

    /** A log counts as a tree log when leaves are within 3 blocks. */
    private boolean isTreeLog(BlockPos pos) {
        return FellSupport.hasNearbyBlock(pos,
            p -> steve.level().getBlockState(p).getBlock() instanceof LeavesBlock, 3);
    }

    // ---- fell mode ----

    private void enterFellMode(List<BlockPos> component) {
        fellMode = true;
        fellLogBlock = targetBlock;
        fellHeight = 0;
        fellStallTicks = 0;
        fellLogs.clear();
        fellLogs.addAll(component);
        debugLog("FELL", "whole-tree felling: " + component.size() + " logs");
        phase = Phase.FELL_ASCEND;
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
        // Find a visible solid material to build the pillar from - but never
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
        finish(false, "No blocks left to climb the tree with");
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
        return "Gather " + targetQuantity + " " + (targetBlock != null ? targetBlock.getName().getString() : "?")
            + " (" + gatheredCount + " found)";
    }
}
