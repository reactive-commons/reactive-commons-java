package org.reactivecommons.async.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.KafkaCustomizations;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.kafka.health.KafkaReactiveHealthIndicator;
import org.reactivecommons.async.kafka.listeners.ApplicationEventListener;
import org.reactivecommons.async.kafka.listeners.ApplicationNotificationsListener;
import org.reactivecommons.async.kafka.listeners.ApplicationTopicListener;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.springframework.boot.ssl.SslBundles;
import reactor.core.publisher.Mono;

public record KafkaBrokerProvider(String domain,
                                  AsyncKafkaProps props,
                                  ReactiveReplyRouter router,
                                  KafkaJacksonMessageConverter converter,
                                  MeterRegistry meterRegistry,
                                  CustomReporter errorReporter,
                                  KafkaReactiveHealthIndicator healthIndicator,
                                  ReactiveMessageListener receiver,
                                  ReactiveMessageSender sender,
                                  DiscardNotifier discardNotifier,
                                  TopologyCreator topologyCreator,
                                  KafkaCustomizations customizations,
                                  SslBundles sslBundles) implements BrokerProvider<AsyncKafkaProps> {
    @Override
    public DomainEventBus getDomainBus() {
        return new KafkaDomainEventBus(sender);
    }

    @Override
    public DirectAsyncGateway getDirectAsyncGateway() {
        return new KafkaDirectAsyncGateway();
    }

    @Override
    public void listenDomainEvents(HandlerResolver resolver) {
        if (!props.getDomain().isIgnoreThisListener() && !resolver.getEventListeners().isEmpty()) {
            ApplicationEventListener eventListener = new ApplicationEventListener(listenerContext(resolver),
                    props.resolveEventsGroupId());
            eventListener.startListener(topologyCreator);
        }
    }

    @Override
    public void listenNotificationEvents(HandlerResolver resolver) {
        if (!resolver.getNotificationListeners().isEmpty()) {
            ApplicationNotificationsListener notificationEventListener = new ApplicationNotificationsListener(
                    listenerContext(resolver), props.getAppName());
            notificationEventListener.startListener(topologyCreator);
        }
    }

    @Override
    public void listenCommands(HandlerResolver resolver) {
        // Implemented in the future
    }

    @Override
    public void listenQueries(HandlerResolver resolver) {
        // May be implemented in the future
    }

    @Override
    public void listenQueues(HandlerResolver resolver) {
        // Not applicable to Kafka: there is no native queue concept. Use listenTopics(...) instead.
    }

    @Override
    public void listenTopics(HandlerResolver resolver) {
        resolver.getTopicListeners().values().forEach(registeredListener -> {
            ApplicationTopicListener topicListener = new ApplicationTopicListener(listenerContext(resolver),
                    registeredListener, props.resolveTopicListenerGroupId());
            topicListener.startListener(topologyCreator);
        });
    }

    @Override
    public void listenReplies() {
        // May be implemented in the future
    }

    @Override
    public Mono<RCHealth> healthCheck() {
        return healthIndicator.health();
    }

    /**
     * What the listeners of this domain share, with the retry policy and the topology setting of its properties.
     */
    private ListenerContext<ReactiveMessageListener> listenerContext(HandlerResolver resolver) {
        return new ListenerContext<>(receiver, resolver, converter,
                new RetryPolicy(props.getWithDLQRetry(), props.getMaxRetries(), props.getRetryDelay()),
                props.getCreateTopology(), discardNotifier, errorReporter);
    }
}
