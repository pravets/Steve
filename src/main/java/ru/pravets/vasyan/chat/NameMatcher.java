package ru.pravets.vasyan.chat;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bot-name matching that tolerates Russian transcriptions of Latin names
 * ("алекс" -> Alex, "стиви"/"стеви" -> Steve) and case differences.
 * Pure helper - unit-testable without Minecraft.
 *
 * <p>Matching order:
 * <ol>
 *   <li>exact match, case-insensitive (also covers Cyrillic bot names);</li>
 *   <li>RU-&gt;EN transliteration of the spoken word, case-insensitive;</li>
 *   <li>transcription dictionary for common names (стиви -&gt; Steve).</li>
 * </ol>
 * Always returns the CANONICAL bot name from the list (never the spoken
 * form), so callers can feed it straight into case-sensitive lookups.</p>
 */
public final class NameMatcher {

    private NameMatcher() {}

    /** Cyrillic -> Latin transliteration (standard Russian system). */
    private static final Map<Character, String> TRANSLIT = Map.ofEntries(
        Map.entry('а', "a"), Map.entry('б', "b"), Map.entry('в', "v"),
        Map.entry('г', "g"), Map.entry('д', "d"), Map.entry('е', "e"),
        Map.entry('ё', "e"), Map.entry('ж', "zh"), Map.entry('з', "z"),
        Map.entry('и', "i"), Map.entry('й', "j"), Map.entry('к', "k"),
        Map.entry('л', "l"), Map.entry('м', "m"), Map.entry('н', "n"),
        Map.entry('о', "o"), Map.entry('п', "p"), Map.entry('р', "r"),
        Map.entry('с', "s"), Map.entry('т', "t"), Map.entry('у', "u"),
        Map.entry('ф', "f"), Map.entry('х', "h"), Map.entry('ц', "ts"),
        Map.entry('ч', "ch"), Map.entry('ш', "sh"), Map.entry('щ', "sch"),
        Map.entry('ъ', ""), Map.entry('ы', "y"), Map.entry('ь', ""),
        Map.entry('э', "e"), Map.entry('ю', "yu"), Map.entry('я', "ya")
    );

    /**
     * Common Russian spoken forms -> canonical Latin names.
     * Keys are lowercase Cyrillic/Latin; values are the canonical spellings
     * a bot can actually have (lowercase).
     */
    private static final Map<String, List<String>> TRANSCRIPTIONS = Map.ofEntries(
        Map.entry("алекс", List.of("alex")),
        Map.entry("саша", List.of("alex", "sasha")),
        Map.entry("васян", List.of("vasyan", "vasyann")),
        Map.entry("вася", List.of("vasyan", "vasyann")),
        Map.entry("боб", List.of("bob")),
        Map.entry("дима", List.of("dima")),
        Map.entry("миша", List.of("mike", "misha")),
        Map.entry("джек", List.of("jack")),
        Map.entry("джон", List.of("john")),
        Map.entry("том", List.of("tom")),
        Map.entry("кейт", List.of("kate")),
        Map.entry("анна", List.of("anna"))
    );

    /**
     * Returns the canonical name from {@code botNames} that matches the spoken
     * form, or {@code null} when nothing matches.
     */
    public static String matchName(String spoken, List<String> botNames) {
        if (spoken == null || botNames == null || botNames.isEmpty()) {
            return null;
        }
        String spokenLower = spoken.toLowerCase(Locale.ROOT);
        String transliterated = transliterate(spokenLower);

        for (String name : botNames) {
            String nameLower = name.toLowerCase(Locale.ROOT);
            // 1. exact, case-insensitive
            if (nameLower.equals(spokenLower)) {
                return name;
            }
            // 2. transliteration
            if (nameLower.equals(transliterated)) {
                return name;
            }
        }
        // 3. transcription dictionary
        List<String> canonical = TRANSCRIPTIONS.get(spokenLower);
        if (canonical != null) {
            for (String name : botNames) {
                if (canonical.contains(name.toLowerCase(Locale.ROOT))) {
                    return name;
                }
            }
        }
        return null;
    }

    /** RU -> EN transliteration of a lowercase string. */
    public static String transliterate(String lower) {
        StringBuilder sb = new StringBuilder();
        for (char c : lower.toCharArray()) {
            String latin = TRANSLIT.get(c);
            sb.append(latin != null ? latin : c);
        }
        return sb.toString();
    }
}
