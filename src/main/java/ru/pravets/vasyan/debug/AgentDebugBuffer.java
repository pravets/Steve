package ru.pravets.vasyan.debug;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory ring buffer of agent events for debugging.
 *
 * <p>Every significant step of the agent pipeline (command received, LLM
 * response, parse result, task execution, action results) is recorded here
 * and can be inspected with {@code /steve debug}.</p>
 *
 * <p>Thread-safe: all access is synchronized on the deque.</p>
 */
public final class AgentDebugBuffer {

    private static final int MAX_ENTRIES = 100;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Deque<String> events = new ArrayDeque<>();

    private AgentDebugBuffer() {}

    /**
     * Records a debug event.
     *
     * @param steveName Steve the event belongs to (or "system" / "?")
     * @param type      Event type, e.g. COMMAND, LLM, PARSE, PLAN, ACTION_START, ACTION_FAIL
     * @param message   Details
     */
    public static void log(String steveName, String type, String message) {
        synchronized (events) {
            String line = String.format("[%s] %s %s: %s",
                LocalTime.now().format(TIME), steveName, type, message);
            events.addLast(line);
            while (events.size() > MAX_ENTRIES) {
                events.removeFirst();
            }
        }
    }

    /**
     * Returns a copy of all recorded events, oldest first.
     */
    public static List<String> getEvents() {
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    /**
     * Returns the last {@code limit} events, oldest first.
     */
    public static List<String> getEvents(int limit) {
        synchronized (events) {
            List<String> all = new ArrayList<>(events);
            int from = Math.max(0, all.size() - limit);
            return new ArrayList<>(all.subList(from, all.size()));
        }
    }

    public static void clear() {
        synchronized (events) {
            events.clear();
        }
    }
}
