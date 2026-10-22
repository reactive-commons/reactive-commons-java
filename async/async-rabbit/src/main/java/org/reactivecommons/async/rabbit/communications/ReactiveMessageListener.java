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

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator) {
        this(receiver, topologyCreator, 250, 250, new SubscriberRegistry(), null, null, null);
    }

    public ReactiveMessageListener(Receiver receiver, TopologyCreator topologyCreator, Integer maxConcurrency,
                                   Integer prefetchCount) {
        this(receiver, topologyCreator, maxConcurrency, prefetchCount, new SubscriberRegistry(), null, null, null);
    }
}
