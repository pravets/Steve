package ru.pravets.vasyan.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NameMatcherTest {

    private static final List<String> BOTS = List.of("Alex", "Vasyan", "Bob", "Алекс");

    @Test
    void exactLatinMatch() {
        assertEquals("Alex", NameMatcher.matchName("Alex", BOTS));
    }

    @Test
    void exactCaseInsensitive() {
        assertEquals("Bob", NameMatcher.matchName("bob", BOTS));
        assertEquals("Alex", NameMatcher.matchName("ALEX", BOTS));
    }

    @Test
    void transliterationMatchesAlex() {
        // BOTS contains no Cyrillic "Алекс" here - the spoken "алекс" must
        // resolve to the LATIN "Alex" (transcription dictionary)
        List<String> latinOnly = List.of("Alex", "Vasyan", "Bob");
        assertEquals("Alex", NameMatcher.matchName("алекс", latinOnly));
    }

    @Test
    void dictionaryMatchesStiviToVasyan() {
        assertEquals("Vasyan", NameMatcher.matchName("васян", BOTS));
        assertEquals("Vasyan", NameMatcher.matchName("вася", BOTS));
    }

    @Test
    void cyrillicBotNameMatchesExact() {
        // Bot named "Алекс" found by "алекс" (case-insensitive)
        List<String> onlyCyrillic = List.of("Алекс");
        assertEquals("Алекс", NameMatcher.matchName("алекс", onlyCyrillic));
    }

    @Test
    void unknownReturnsNull() {
        assertNull(NameMatcher.matchName("никто", BOTS));
        assertNull(NameMatcher.matchName("zzz", BOTS));
    }

    @Test
    void emptyInputReturnsNull() {
        assertNull(NameMatcher.matchName("", BOTS));
        assertNull(NameMatcher.matchName(null, BOTS));
        assertNull(NameMatcher.matchName("alex", List.of()));
    }

    @Test
    void transliterateRoundTrip() {
        // Per-character transliteration is literal: к+с -> ks
        assertEquals("aleks", NameMatcher.transliterate("алекс"));
        assertEquals("vasyan", NameMatcher.transliterate("васян"));
    }
}
