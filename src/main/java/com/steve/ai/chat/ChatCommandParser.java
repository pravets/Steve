package com.steve.ai.chat;

import java.util.List;
import java.util.Locale;

/**
 * Pure helpers for interpreting natural-language commands coming from the
 * K-panel chat. No Minecraft dependencies - unit-testable.
 */
public final class ChatCommandParser {

    /** Command prefixes that address ALL Steves (lowercase, with trailing space). */
    private static final List<String> ALL_PREFIXES = List.of(
        "all steves ", "all ", "everyone ", "everybody ",
        "все боты ", "всем ", "все "
    );

    /** First words that mean "stop / stay in place". */
    private static final List<String> STAY_WORDS = List.of(
        "stay", "stop", "wait", "freeze",
        "стой", "замри", "остановись", "стоять", "жди"
    );

    private ChatCommandParser() {}

    /**
     * Whether the (already lowercased) command is addressed to all Steves:
     * "all teleport to me", "everyone come", "все телепортируйтесь ко мне", ...
     */
    public static boolean isAllCommand(String lowerCommand) {
        for (String prefix : ALL_PREFIXES) {
            if (lowerCommand.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the (already lowercased) command starts with a stay/stop word:
     * "stay", "stop", "wait here", "стой", "замри на месте", ...
     */
    public static boolean isStayCommand(String lowerCommand) {
        String trimmed = lowerCommand.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String firstWord = trimmed.split("\\s+")[0];
        return STAY_WORDS.contains(firstWord);
    }

    /** Convenience: lowercase with ROOT locale (locale-independent). */
    public static String normalize(String command) {
        return command.toLowerCase(Locale.ROOT);
    }

    /**
     * Removes the "all ..." addressing prefix from a command, e.g.
     * "all stay" / "все телепортируйтесь ко мне" -> "stay" / "телепортируйтесь ко мне".
     * Used when forwarding to the server via "tell all", so the payload the
     * Steves receive does not start with the addressing word.
     */
    public static String stripAllPrefix(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        for (String prefix : ALL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return command.substring(prefix.length()).trim();
            }
        }
        return command;
    }
}
