package org.reactivecommons.async.starter.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReactiveCommonsFeatures {

    /**
     * Equivalent to {@code @EnableEventListeners}.
     * Start listening to domain events from the broker.
     */
    private boolean listenEvents;

    /**
     * Equivalent to {@code @EnableNotificationListener}.
     * Start listening to notification events from the broker.
     */
    private boolean listenNotificationEvents;

    /**
     * Equivalent to {@code @EnableCommandListeners}.
     * Start listening to commands from the broker.
     */
    private boolean listenCommands;

    /**
     * Equivalent to {@code @EnableQueryListeners}.
     * Start listening to queries from the broker.
     */
    private boolean listenQueries;

    /**
     * Equivalent to {@code @EnableQueueListeners}.
     * Start listening to queues from the broker.
     */
    private boolean listenQueues;

    /**
     * Equivalent to {@code @EnableDomainEventBus}.
     * Exposes a {@link org.reactivecommons.api.domain.DomainEventBus} bean for publishing events.
     */
    private boolean sendEvents;

    /**
     * Equivalent to {@code @EnableDirectAsyncGateway}.
     * Exposes a {@link org.reactivecommons.async.api.DirectAsyncGateway} bean for sending
     * commands and request-reply queries.
     */
    private boolean sendCommands;
}
