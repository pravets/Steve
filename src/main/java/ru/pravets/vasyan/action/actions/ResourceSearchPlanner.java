package ru.pravets.vasyan.action.actions;

import net.minecraft.core.BlockPos;

/**
 * Pure routing logic for resource search: the Steve walks a spiral of
 * "stations" around the origin (rings of increasing radius, a few look-out
 * points per ring), scanning at each station. No Minecraft world access -
 * unit-testable.
 */
public final class ResourceSearchPlanner {

    /**
     * Station altitude offset: Steve walks on the ground (never flies), so
     * stations sit exactly at ground level - an elevated station only confuses
     * ground navigation and adds nothing (vision scans from the ground).
     */
    public static final int STATION_HEIGHT_OFFSET = 0;

    private ResourceSearchPlanner() {}

    /**
     * Immutable search cursor: which station of which ring we are at,
     * and when the search started (tick count).
     */
    public record SearchState(BlockPos origin, int ringIndex, int stationIndex, long startedAtTick) {}

    /**
     * Whether another station exists: the next ring's radius must not exceed
     * {@code maxRadius}.
     */
    public static boolean hasNext(SearchState state, int maxRadius, int ringSpacing) {
        return (state.ringIndex() + 1) * ringSpacing <= maxRadius;
    }

    /**
     * Station position for the given state: radius = ringSpacing * (ring+1),
     * stationIndex evenly spread over the ring, y = origin.y + STATION_HEIGHT_OFFSET.
     */
    public static BlockPos stationFor(SearchState state, int ringSpacing, int stationsPerRing) {
        int radius = ringSpacing * (state.ringIndex() + 1);
        double angle = 2.0 * Math.PI * state.stationIndex() / stationsPerRing;
        int x = state.origin().getX() + (int) Math.round(radius * Math.cos(angle));
        int z = state.origin().getZ() + (int) Math.round(radius * Math.sin(angle));
        int y = state.origin().getY() + STATION_HEIGHT_OFFSET;
        return new BlockPos(x, y, z);
    }

    /**
     * Advances to the next station (or the first station of the next ring).
     * Origin and start tick are preserved.
     */
    public static SearchState next(SearchState state, int stationsPerRing) {
        if (state.stationIndex() + 1 < stationsPerRing) {
            return new SearchState(state.origin(), state.ringIndex(), state.stationIndex() + 1,
                state.startedAtTick());
        }
        return new SearchState(state.origin(), state.ringIndex() + 1, 0, state.startedAtTick());
    }

    /** Whether the search has run longer than {@code maxSearchTicks} ticks. */
    public static boolean isTimedOut(SearchState state, long currentTick, long maxSearchTicks) {
        return currentTick - state.startedAtTick() >= maxSearchTicks;
    }
}
