package org.reactivecommons.async.starter.broker;

import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.reactivecommons.async.starter.props.GenericAsyncProps;
import reactor.core.publisher.Mono;

@SuppressWarnings("rawtypes")
public interface BrokerProvider<T extends GenericAsyncProps> {
    T props();

    DomainEventBus getDomainBus();

    DirectAsyncGateway getDirectAsyncGateway();

    void listenDomainEvents(HandlerResolver resolver);

    void listenNotificationEvents(HandlerResolver resolver);

    void listenCommands(HandlerResolver resolver);

    void listenQueries(HandlerResolver resolver);

    /**
     * Starts the raw queue listeners registered with {@code HandlerRegistry.listenQueue(...)}. Only meaningful
     * for RabbitMQ.
     */
    void listenQueues(HandlerResolver resolver);

    /**
     * Starts the raw topic listeners registered with {@code HandlerRegistry.listenTopic(...)}. Only meaningful
     * for Kafka.
     */
    void listenTopics(HandlerResolver resolver);

    void listenReplies();

    Mono<RCHealth> healthCheck();

    /**
     * Starts consuming with the listeners registered by the {@code listen*} methods, which only register them. Called
     * once the application context has created and initialized every bean, so no handler runs while it is being
     * refreshed; a listener registered afterwards starts right away. The default implementation does nothing: the
     * listeners of a provider that does not override it start as soon as they are registered.
     */
    default void start() {
    }

    /**
     * Stops the listeners and releases the resources (threads, connections) owned by this provider. Called when the
     * application context closes. The default implementation does nothing.
     */
    default void close() {
    }
}
