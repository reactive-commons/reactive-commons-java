package org.reactivecommons.async.commons.utils.matcher;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;


class KeyMatcherTest {
    private Matcher keyMatcher;
    private Set<String> listeners;

    @BeforeEach
    public void init() {
        keyMatcher = new KeyMatcher();
        listeners = new HashSet<>();
        listeners.add("A.*");
        listeners.add("A.B");
        listeners.add("A.B.*");
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
        assertEquals("A.*", match);
    }

    @Test
    void matchDefaultForNonExistentSecondLevel() {
        String nonExistentTarget = "A.B.X.Y.Z";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.B.*", match);
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
        listeners.add("prefix.*.*");
        listeners.add("*.middle.*");
        listeners.add("*.*.suffix");
        listeners.add("*.middle.suffix");
        listeners.add("prefix.*.suffix");
        listeners.add("prefix.middle.*");
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
        assertEquals("prefix.middle.*", keyMatcher.match(listeners, "prefix.middle.any.any"));
        assertEquals("prefix.*.suffix", keyMatcher.match(listeners, "prefix.any.suffix"));
        assertEquals("prefix.*.suffix", keyMatcher.match(listeners, "prefix.any.any.suffix"));
        assertEquals("*.middle.suffix", keyMatcher.match(listeners, "any.middle.suffix"));
        assertEquals("*.middle.suffix", keyMatcher.match(listeners, "any.any.middle.suffix"));
        assertEquals("*.*.suffix", keyMatcher.match(listeners, "any.any.suffix"));
        assertEquals("*.*.suffix", keyMatcher.match(listeners, "any.any.any.suffix"));
        assertEquals("*.middle.*", keyMatcher.match(listeners, "any.middle.any"));
        assertEquals("*.middle.*", keyMatcher.match(listeners, "any.any.middle.any.any"));
        assertEquals("prefix.*.*", keyMatcher.match(listeners, "prefix.any.any"));
        assertEquals("prefix.*.*", keyMatcher.match(listeners, "prefix.any.any.any.any"));
        assertEquals("*.*.*", keyMatcher.match(listeners, "any.any.any"));
        assertEquals("*.*.*", keyMatcher.match(listeners, "any.any.any.any.any"));
    }
}
