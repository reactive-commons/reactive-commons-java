package org.reactivecommons.async.commons.utils.matcher;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class KeyMatcherTest {
    private Matcher keyMatcher;
    private Set<String> listeners;

    @BeforeEach
    void init() {
        keyMatcher = new KeyMatcher();
        listeners = new HashSet<>();
        listeners.add("A.*");
        listeners.add("A.#");
        listeners.add("A.B");
        listeners.add("A.B.*");
        listeners.add("A.B.#");
        listeners.add("A.B.C");
        listeners.add("A.B.*.D");
        listeners.add("A.B.C.D");
        listeners.add("W.X.Y");
        listeners.add("app.event-prefix.any");
    }

    @Test
    void matchNonExistentFirstLevel() {
        String nonExistentTarget = "A.X";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.*", match);
    }

    @Test
    void matchExistentFirstLevel() {
        String existentTarget = "A.B";
        final String match = keyMatcher.match(listeners, existentTarget);
        assertEquals("A.B", match);
    }

    @Test
    void matchNonExistentSecondLevel() {
        String nonExistentTarget = "A.B.X";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.B.*", match);
    }

    @Test
    void matchExistentAnotherThirdLevel() {
        String nonExistentTarget = "A.B.X.Y";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.B.#", match);
    }

    @Test
    void matchExistentSecondLevel() {
        String existentTarget = "A.B.C";
        final String match = keyMatcher.match(listeners, existentTarget);
        assertEquals("A.B.C", match);
    }

    @Test
    void matchNonExistentThirdLevel() {
        String nonExistentTarget = "A.B.X.D";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.B.*.D", match);
    }

    @Test
    void matchExistentThirdLevel() {
        String existentTarget = "A.B.C.D";
        final String match = keyMatcher.match(listeners, existentTarget);
        assertEquals("A.B.C.D", match);
    }

    @Test
    void matchDefaultForNonExistent() {
        String nonExistentTarget = "A.W.X.Y.Z";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.#", match);
    }

    @Test
    void matchDefaultForNonExistentSecondLevel() {
        String nonExistentTarget = "A.B.X.Y.Z";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.B.#", match);
    }

    @Test
    void shouldNotMatch() {
        String nonExistentTarget = "X.Y.Z";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals(nonExistentTarget, match);
    }

    @Test
    void shouldNotMatchWhenNoWildcard() {
        String nonExistentTarget = "W.X.Y.Z";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals(nonExistentTarget, match);
    }

    @Test
    void shouldNotMatchWhenNoWildcardSameLength() {
        String nonExistentTarget = "app.event.test";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals(nonExistentTarget, match);
    }

    @Test
    void shouldApplyPriority() {
        listeners = new HashSet<>();
        listeners.add("*.*.*");
        listeners.add("*.#.*");
        listeners.add("prefix.*.*");
        listeners.add("prefix.*.#");
        listeners.add("*.*.suffix");
        listeners.add("#.*.suffix");
        listeners.add("*.middle.suffix");
        listeners.add("#.middle.suffix");
        listeners.add("*.middle.*");
        listeners.add("*.middle.#");
        listeners.add("#.middle.#");
        listeners.add("prefix.*.suffix");
        listeners.add("prefix.#.suffix");
        listeners.add("prefix.middle.*");
        listeners.add("prefix.middle.#");
        listeners.add("prefix.middle.suffix");
        listeners.add("prefix.other.other");
        listeners.add("other.middle.other");
        listeners.add("other.other.suffix");
        listeners.add("other.other.other");
        listeners.add("in.depend.ent");
        assertEquals("in.depend.ent", keyMatcher.match(listeners, "in.depend.ent"));
        assertEquals("other.other.other", keyMatcher.match(listeners, "other.other.other"));
        assertEquals("other.other.suffix", keyMatcher.match(listeners, "other.other.suffix"));
        assertEquals("other.middle.other", keyMatcher.match(listeners, "other.middle.other"));
        assertEquals("prefix.other.other", keyMatcher.match(listeners, "prefix.other.other"));
        assertEquals("prefix.middle.suffix", keyMatcher.match(listeners, "prefix.middle.suffix"));
        assertEquals("prefix.middle.*", keyMatcher.match(listeners, "prefix.middle.any"));
        assertEquals("prefix.middle.#", keyMatcher.match(listeners, "prefix.middle.any.any"));
        assertEquals("prefix.*.suffix", keyMatcher.match(listeners, "prefix.any.suffix"));
        assertEquals("prefix.#.suffix", keyMatcher.match(listeners, "prefix.any.any.suffix"));
        assertEquals("*.middle.suffix", keyMatcher.match(listeners, "any.middle.suffix"));
        assertEquals("#.middle.suffix", keyMatcher.match(listeners, "any.any.middle.suffix"));
        assertEquals("*.*.suffix", keyMatcher.match(listeners, "any.any.suffix"));
        assertEquals("#.*.suffix", keyMatcher.match(listeners, "any.any.any.suffix"));
        assertEquals("*.middle.*", keyMatcher.match(listeners, "any.middle.any"));
        assertEquals("#.middle.#", keyMatcher.match(listeners, "any.any.middle.any.any"));
        assertEquals("prefix.*.*", keyMatcher.match(listeners, "prefix.any.any"));
        assertEquals("prefix.*.#", keyMatcher.match(listeners, "prefix.any.any.any.any"));
        assertEquals("*.*.*", keyMatcher.match(listeners, "any.any.any"));
        assertEquals("*.#.*", keyMatcher.match(listeners, "any.any.any.any.any"));
    }

    @Test
    void theMostSpecificPatternWinsWhicheverOrderTheCandidatesArriveIn() {
        // a literal word beats a wildcard, in either position of the comparison
        assertSameWinnerInBothOrders("a.*", "*.b", "a.b", "a.*");
        // '*' beats '#' at the same word
        assertSameWinnerInBothOrders("a.#", "a.*", "a.x", "a.*");
        // after '#' against '*', the next words still decide: the literal 'a' beats the '*'
        assertSameWinnerInBothOrders("#.a", "*.*", "x.a", "#.a");
        // two wildcard pairs in a row ('#' against '*', then '*' against '#'): the literal 'suffix' decides
        assertSameWinnerInBothOrders("#.*.suffix", "*.#.*", "any.any.any.suffix", "#.*.suffix");
        // equal words up to the end of the shorter pattern: the longer pattern wins
        assertSameWinnerInBothOrders("a.#", "a.#.c", "a.x.c", "a.#.c");
    }

    private static void assertSameWinnerInBothOrders(String first, String second, String target, String winner) {
        assertEquals(winner, KeyMatcher.matchMissingKey(new LinkedHashSet<>(List.of(first, second)), target));
        assertEquals(winner, KeyMatcher.matchMissingKey(new LinkedHashSet<>(List.of(second, first)), target));
    }

    @Test
    void theCompiledPatternsAreTheWildcardNamesNeverTheRoutingKeys() throws Exception {
        for (int i = 0; i < 5_000; i++) {
            assertEquals("A.B.*", keyMatcher.match(listeners, "A.B.key" + i));
        }

        // the patterns are cached by name: looking up many routing keys does not grow the cache
        Set<String> compiled = compiledPatterns().keySet();
        assertTrue(compiled.containsAll(Set.of("A.*", "A.#", "A.B.*", "A.B.#", "A.B.*.D")));
        assertTrue(compiled.stream().noneMatch(pattern -> pattern.contains("key")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Pattern> compiledPatterns() throws Exception {
        Field patterns = KeyMatcher.class.getDeclaredField("PATTERNS");
        patterns.setAccessible(true);
        return (Map<String, Pattern>) patterns.get(null);
    }
}
