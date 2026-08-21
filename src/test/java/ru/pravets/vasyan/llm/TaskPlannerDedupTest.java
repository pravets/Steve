package ru.pravets.vasyan.llm;

import ru.pravets.vasyan.action.Task;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TaskPlanner's deterministic gather-task normalization:
 * wood-collapse (per-type log tasks -> a single any-log task) and duplicate
 * gather removal after the stack/wood overrides turn distinct LLM tasks
 * into identical ones ("наруби стак дерева" -> 2x "gather wood x64").
 *
 * <p>Plain objects only - no Minecraft bootstrap required.</p>
 */
class TaskPlannerDedupTest {

    private static Task gather(String resource, int quantity) {
        Map<String, Object> params = new HashMap<>();
        params.put("resource", resource);
        params.put("quantity", quantity);
        return new Task("gather", params);
    }

    private static Task gatherWithFill(String resource, boolean fill) {
        Map<String, Object> params = new HashMap<>();
        params.put("resource", resource);
        params.put("fill", fill);
        return new Task("gather", params);
    }

    private static Task follow(String player) {
        Map<String, Object> params = new HashMap<>();
        params.put("player", player);
        return new Task("follow", params);
    }

    // ---- dedupeGatherTasks ----

    @Test
    void twoIdenticalGathersDedupeToOne() {
        List<Task> result = TaskPlanner.dedupeGatherTasks(List.of(
            gather("wood", 64),
            gather("wood", 64)));

        assertEquals(1, result.size());
        assertEquals("gather", result.get(0).getAction());
        assertEquals("wood", result.get(0).getStringParameter("resource"));
        assertEquals(64, result.get(0).getIntParameter("quantity", -1));
    }

    @Test
    void threeIdenticalGathersDedupeToOne() {
        List<Task> result = TaskPlanner.dedupeGatherTasks(List.of(
            gather("wood", 64),
            gather("wood", 64),
            gather("wood", 64)));

        assertEquals(1, result.size());
    }

    @Test
    void differentResourcesAreBothKept() {
        List<Task> result = TaskPlanner.dedupeGatherTasks(List.of(
            gather("iron_ore", 16),
            gather("coal", 16)));

        assertEquals(2, result.size());
        assertEquals("iron_ore", result.get(0).getStringParameter("resource"));
        assertEquals("coal", result.get(1).getStringParameter("resource"));
    }

    @Test
    void differentFillFlagIsNotADuplicate() {
        List<Task> result = TaskPlanner.dedupeGatherTasks(List.of(
            gatherWithFill("wood", true),
            gather("wood", 64)));

        assertEquals(2, result.size());
    }

    @Test
    void gatherFollowSameGatherDedupesToTwoTasks() {
        List<Task> result = TaskPlanner.dedupeGatherTasks(List.of(
            gather("oak_log", 64),
            follow("Alex"),
            gather("oak_log", 64)));

        assertEquals(2, result.size());
        assertEquals("gather", result.get(0).getAction());
        assertEquals("follow", result.get(1).getAction());
        assertEquals("Alex", result.get(1).getStringParameter("player"));
    }

    // ---- collapseWoodGatherTasks ----

    @Test
    void woodCollapseOfOakAndBirchKeepsFirstQuantity() {
        List<Task> result = TaskPlanner.collapseWoodGatherTasks(List.of(
            gather("oak_log", 32),
            gather("birch_log", 64)));

        assertEquals(1, result.size());
        assertEquals("gather", result.get(0).getAction());
        assertEquals("wood", result.get(0).getStringParameter("resource"));
        assertEquals(32, result.get(0).getIntParameter("quantity", -1));
    }

    @Test
    void woodCollapsePreservesInterleavedNonGatherTask() {
        List<Task> result = TaskPlanner.collapseWoodGatherTasks(List.of(
            gather("oak_log", 64),
            follow("Alex"),
            gather("birch_log", 64)));

        assertEquals(2, result.size());
        assertEquals("gather", result.get(0).getAction());
        assertEquals("wood", result.get(0).getStringParameter("resource"));
        assertEquals("follow", result.get(1).getAction());
        assertEquals("Alex", result.get(1).getStringParameter("player"));
    }
}
