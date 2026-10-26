package org.reactivecommons.async.rabbit.communications;

import org.junit.jupiter.api.Test;
import reactor.rabbitmq.Receiver;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ReactiveMessageListenerTest {

    private final Receiver receiver = mock(Receiver.class);
    private final TopologyCreator topologyCreator = mock(TopologyCreator.class);

    @Test
    void aPrefetchCountBelowOneIsRejected() {
        for (Integer prefetchCount : new Integer[]{0, -1, null}) {
            assertThatThrownBy(() -> new ReactiveMessageListener(receiver, topologyCreator, 250, prefetchCount))
                    .as("prefetchCount %s", prefetchCount)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("prefetchCount must be at least 1");
        }
    }

    @Test
    void aMaxConcurrencyBelowOneIsRejected() {
        for (Integer maxConcurrency : new Integer[]{0, -1, null}) {
            assertThatThrownBy(() -> new ReactiveMessageListener(receiver, topologyCreator, maxConcurrency, 250))
                    .as("maxConcurrency %s", maxConcurrency)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("maxConcurrency must be at least 1");
        }
    }

    @Test
    void positiveValuesAreAccepted() {
        assertThatCode(() -> new ReactiveMessageListener(receiver, topologyCreator, 1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> new ReactiveMessageListener(receiver, topologyCreator)).doesNotThrowAnyException();
    }
}
