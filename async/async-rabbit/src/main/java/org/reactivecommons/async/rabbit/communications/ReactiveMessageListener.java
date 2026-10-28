package org.reactivecommons.async.rabbit.communications;

import io.micrometer.core.instrument.MeterRegistry;
import org.reactivecommons.async.rabbit.listeners.SubscriberRegistry;
import reactor.rabbitmq.Receiver;

import java.time.Duration;

/**
 * The receiver and topology creator a listener uses to consume from one domain, plus the per-domain hooks the
 * listeners wire into for observability: the {@link SubscriberRegistry} they publish their liveness to, the
 * {@link MeterRegistry} they emit metrics on, and the handler timeout they enforce.
 */
public record ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator, Integer maxConcurrency,
                                      Integer prefetchCount, SubscriberRegistry subscriberRegistry,
                                      MeterRegistry meterRegistry, String domain, Duration handlerTimeout) {

    public ReactiveMessageListener {
        if (prefetchCount == null || prefetchCount < 1) {
            // a prefetch of 0 means no limit: the broker would send every message of the queue to the listener
            throw new IllegalArgumentException("prefetchCount must be at least 1 (250 by default), not "
                    + prefetchCount + ": with 0 the broker sends every message of the queue without waiting for "
                    + "their acknowledgements");
        }
        if (maxConcurrency == null || maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1 (250 by default), not "
                    + maxConcurrency);
        }
    }

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator) {
        this(receiver, topologyCreator, 250, 250, new SubscriberRegistry(), null, null, null);
    }

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator, Integer maxConcurrency,
                                   Integer prefetchCount) {
        this(receiver, topologyCreator, maxConcurrency, prefetchCount, new SubscriberRegistry(), null, null, null);
    }
}
