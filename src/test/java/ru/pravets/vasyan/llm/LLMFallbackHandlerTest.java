package ru.pravets.vasyan.llm;

import ru.pravets.vasyan.action.Task;
import ru.pravets.vasyan.llm.resilience.LLMFallbackHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that fallback responses are valid for the ResponseParser
 * and carry parameters accepted by TaskPlanner.validateTask.
 */
class LLMFallbackHandlerTest {

    private static final Set<String> VALID_ACTIONS =
        Set.of("mine", "build", "attack", "follow", "place", "pathfind", "craft", "gather");

    private final LLMFallbackHandler handler = new LLMFallbackHandler();

    private ResponseParser.ParsedResponse parse(String prompt) {
        String json = handler.generateFallback(prompt, new RuntimeException("test")).getContent();
        ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(json);
        assertNotNull(parsed, "Fallback JSON must parse for prompt: " + prompt);
        return parsed;
    }

    @Test
    void miningPatternProducesValidMineTask() {
        ResponseParser.ParsedResponse parsed = parse("mine some iron ore");
        assertEquals(1, parsed.getTasks().size());
        assertEquals("mine", parsed.getTasks().get(0).getAction());
        assertTrue(parsed.getTasks().get(0).hasParameters("block", "quantity"));
    }

    @Test
    void buildPatternProducesValidBuildTask() {
        ResponseParser.ParsedResponse parsed = parse("build a house for me");
        assertEquals("build", parsed.getTasks().get(0).getAction());
        assertTrue(parsed.getTasks().get(0).hasParameters("structure", "blocks", "dimensions"));
    }

    @Test
    void combatPatternProducesValidAttackTask() {
        ResponseParser.ParsedResponse parsed = parse("kill the zombies");
        assertEquals("attack", parsed.getTasks().get(0).getAction());
        assertTrue(parsed.getTasks().get(0).hasParameters("target"));
    }

    @Test
    void followPatternProducesValidFollowTask() {
        ResponseParser.ParsedResponse parsed = parse("follow me");
        assertEquals("follow", parsed.getTasks().get(0).getAction());
        assertTrue(parsed.getTasks().get(0).hasParameters("player"));
    }

    @Test
    void movementPatternFallsBackToFollow() {
        ResponseParser.ParsedResponse parsed = parse("go to the village");
        assertEquals("follow", parsed.getTasks().get(0).getAction());
        assertTrue(parsed.getTasks().get(0).hasParameters("player"));
    }

    @Test
    void stopPatternUsesSafeDefault() {
        ResponseParser.ParsedResponse parsed = parse("stop what you are doing");
        assertEquals("follow", parsed.getTasks().get(0).getAction());
    }

    @Test
    void unknownPromptUsesSafeDefault() {
        ResponseParser.ParsedResponse parsed = parse("hello there");
        assertEquals("follow", parsed.getTasks().get(0).getAction());
    }

    @Test
    void emptyPromptUsesSafeDefault() {
        ResponseParser.ParsedResponse parsed = parse("");
        assertEquals("follow", parsed.getTasks().get(0).getAction());
    }

    @Test
    void allGeneratedTasksHaveValidActionsAndParameters() {
        List<String> prompts = List.of(
            "mine iron", "dig for diamonds", "build a castle", "construct a house",
            "attack monsters", "kill creeper", "follow me", "come here",
            "go to the nether portal", "place a torch", "stop", "random gibberish"
        );
        for (String prompt : prompts) {
            ResponseParser.ParsedResponse parsed = parse(prompt);
            assertFalse(parsed.getTasks().isEmpty(), "Tasks must not be empty for: " + prompt);
            for (Task task : parsed.getTasks()) {
                assertTrue(VALID_ACTIONS.contains(task.getAction()),
                    "Action " + task.getAction() + " must be valid (prompt: " + prompt + ")");
                assertNotNull(task.getParameters(), "Parameters must not be null");
            }
        }
    }
}
