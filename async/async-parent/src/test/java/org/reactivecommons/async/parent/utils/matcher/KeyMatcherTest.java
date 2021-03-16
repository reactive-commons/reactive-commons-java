package org.reactivecommons.async.parent.utils.matcher;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class KeyMatcherTest {
    private KeyMatcher keyMatcher;
    private Set<String> listeners;

    @BeforeEach
    public void init() {
        keyMatcher =  new KeyMatcher();
        listeners = new HashSet<>();
        listeners.add("A.*");
        listeners.add("A.B");
        listeners.add("A.B.*");
        listeners.add("A.B.C");
        listeners.add("A.B.*.D");
        listeners.add("A.B.C.D");
    }

    @Test
    public void matchNonExistentFirstLevel() {
        String nonExistentTarget = "A.X";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.*", match);
    }

    @Test
    public void matchExistentFirstLevel() {
        String existentTarget = "A.B";
        final String match = keyMatcher.match(listeners, existentTarget);
        assertEquals("A.B", match);
    }

    @Test
    public void matchNonExistentSecondLevel() {
        String nonExistentTarget = "A.B.X";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.B.*", match);
    }

    @Test
    public void matchExistentSecondLevel() {
        String existentTarget = "A.B.C";
        final String match = keyMatcher.match(listeners, existentTarget);
        assertEquals("A.B.C", match);
    }

    @Test
    public void matchNonExistentThirdLevel() {
        String nonExistentTarget = "A.B.X.D";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.B.*.D", match);
    }

    @Test
    public void matchExistentThirdLevel() {
        String existentTarget = "A.B.C.D";
        final String match = keyMatcher.match(listeners, existentTarget);
        assertEquals("A.B.C.D", match);
    }

    @Test
    public void matchDefaultForNonExistent() {
        String nonExistentTarget = "A.W.X.Y.Z";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.*", match);
    }

    @Test
    public void matchDefaultForNonExistentSecondLevel() {
        String nonExistentTarget = "A.B.X.Y.Z";
        final String match = keyMatcher.match(listeners, nonExistentTarget);
        assertEquals("A.B.*", match);
    }
}