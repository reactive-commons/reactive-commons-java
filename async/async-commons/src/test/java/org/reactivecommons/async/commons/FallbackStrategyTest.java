package org.reactivecommons.async.commons;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackStrategyTest {

    @Test
    void fastRetryHasMessage() {
        assertThat(FallbackStrategy.FAST_RETRY.message).contains("Fast retry");
    }

    @Test
    void definitiveDiscardHasMessage() {
        assertThat(FallbackStrategy.DEFINITIVE_DISCARD.message).contains("DEFINITIVE DISCARD");
    }

    @Test
    void retryDlqHasMessage() {
        assertThat(FallbackStrategy.RETRY_DLQ.message).contains("Retry DLQ");
    }

    @Test
    void valuesReturnsAllStrategies() {
        assertThat(FallbackStrategy.values()).hasSize(3);
    }

    @Test
    void valueOfReturnsCorrect() {
        assertThat(FallbackStrategy.valueOf("FAST_RETRY")).isEqualTo(FallbackStrategy.FAST_RETRY);
    }
}
