package com.steve.ai.action.actions;

import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.VisionScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;

import java.util.Comparator;
import java.util.List;

/**
 * Resource gathering by ROUTING, not tunnel-digging.
 *
 * <p>The Steve walks (ground navigation, never flying) a spiral of look-out
 * stations around the start point, scanning with vision at each station
 * ({@link VisionScanner#findVisible}) and mining ONLY visible target blocks.
 * No tunnels are ever dug.</p>
 *
 * <p>Stops early when: enough blocks gathered, inventory full, search timed
 * out, or the route is exhausted (nothing found within the search radius).</p>
 */
public class GatherResourceAction extends BaseAction {

    private static final double ARRIVED_DISTANCE_SQ = 3.0 * 3.0;
    private static final double MINE_REACH_SQ = 5.0 * 5.0;
    private static final int ROUTE_STALL_TICKS = 60; // give navigation time to build a path
    private static final int MINE_STALL_TICKS = 60; // unreachable visible ore grace period

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

    private enum Phase { SEARCH, ROUTING, MINING, FINISHED }

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

        if (gatheredCount >= targetQuantity) {
            finish(true, "Gathered " + gatheredCount + " " + targetBlock.getName().getString());
            return;
        }

        switch (phase) {
            case SEARCH -> phaseSearch();
            case ROUTING -> phaseRouting();
            case MINING -> phaseMining();
            default -> { }
        }
    }

    // ---- phases ----

    private void phaseSearch() {
        List<BlockPos> visible = VisionScanner.findVisible(steve, targetBlock);
        if (!visible.isEmpty()) {
            // Nearest visible target wins
            mineTarget = visible.stream()
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
            // period instead of re-pathfinding forever until the global timeout.
            ticksOnMine++;
            if (ticksOnMine > MINE_STALL_TICKS && steve.getNavigation().isDone()) {
                ticksOnMine = 0;
                steve.getNavigation().stop();
                mineTarget = null;
                phase = Phase.SEARCH;
            }
            return;
        }

        // In reach: break ONLY this block (no tunneling)
        steve.swing(InteractionHand.MAIN_HAND, true);
        steve.level().destroyBlock(mineTarget, true);
        gatheredCount++;
        ticksOnMine = 0;
        debugLog("MINE", targetBlock.getName().getString() + " at " + mineTarget
            + " (" + gatheredCount + "/" + targetQuantity + ")");

        mineTarget = null;
        phase = Phase.SEARCH; // look for the next visible block
    }

    // ---- helpers ----

    private void finish(boolean success, String message) {
        phase = Phase.FINISHED;
        steve.getNavigation().stop();
        steve.setFlying(false);
        result = success ? ActionResult.success(message) : ActionResult.failure(message);
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

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
        steve.setFlying(false);
    }

    @Override
    public String getDescription() {
        return "Gather " + targetQuantity + " " + (targetBlock != null ? targetBlock.getName().getString() : "?")
            + " (" + gatheredCount + " found)";
    }
}
