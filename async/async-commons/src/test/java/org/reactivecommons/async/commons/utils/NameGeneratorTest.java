package org.reactivecommons.async.commons.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameGeneratorTest {

    @Test
    void generateNameFromWithoutSuffix() {
        String result = NameGenerator.generateNameFrom("application");
        assertFalse(result.contains("="));
        assertTrue(result.startsWith("application."));
        assertEquals(34, result.length());
    }

    @Test
    void generateNameFromWithSuffix() {
        String result = NameGenerator.generateNameFrom("application", "suffix");
        assertFalse(result.contains("="));
        assertTrue(result.startsWith("application.suffix."));
        assertEquals(41, result.length());
    }
}
