package org.reactivecommons.async.kafka;

import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.kafka.health.KafkaReactiveHealthIndicator;
import org.reactivecommons.async.kafka.listeners.ApplicationEventListener;
import org.reactivecommons.async.kafka.listeners.ApplicationNotificationsListener;
import org.reactivecommons.async.kafka.listeners.ApplicationTopicListener;
import org.reactivecommons.async.kafka.listeners.GenericMessageListener;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The publisher and the listeners of a Kafka domain. Closing it stops the listeners and closes the producer, the admin
 * clients and the threads of the domain.
 */
public final class KafkaBrokerProvider implements BrokerProvider<AsyncKafkaProps> {
    /**
     * How many times a failed message is retried before it is sent to its DLQ, when {@code withDLQRetry} is true and
     * {@code maxRetries} is left to its default (-1).
     */
    public static final int DEFAULT_DLQ_MAX_RETRIES = 10;

    private final String domain;
    private final AsyncKafkaProps props;
    private final Components components;
    private final KafkaJacksonMessageConverter converter;
    private final CustomReporter errorReporter;
    private final KafkaReactiveHealthIndicator healthIndicator;
    private final ReactiveMessageListener receiver;
    private final ReactiveMessageSender sender;
    private final DiscardNotifier discardNotifier;
    private final TopologyCreator topologyCreator;
    private final Runnable releaseDiscardProvider;
    private final List<GenericMessageListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * What the provider uses to publish, consume and report on the cluster of its domain.
     */
    public record Components(KafkaJacksonMessageConverter converter, CustomReporter errorReporter,
                             KafkaReactiveHealthIndicator healthIndicator, ReactiveMessageListener receiver,
                             ReactiveMessageSender sender, DiscardNotifier discardNotifier,
                             TopologyCreator topologyCreator) {
    }

    public KafkaBrokerProvider(String domain, AsyncKafkaProps props, Components components) {
        this(domain, props, components, () -> {
        });
    }

    /**
     * @param releaseDiscardProvider run once the provider is closed, to release the shared discard provider it uses
     */
    public KafkaBrokerProvider(String domain, AsyncKafkaProps props, Components components,
                               Runnable releaseDiscardProvider) {
        this.domain = domain;
        this.props = props;
        this.components = components;
        this.converter = components.converter();
        this.errorReporter = components.errorReporter();
        this.healthIndicator = components.healthIndicator();
        this.receiver = components.receiver();
        this.sender = components.sender();
        this.discardNotifier = components.discardNotifier();
        this.topologyCreator = components.topologyCreator();
        this.releaseDiscardProvider = releaseDiscardProvider;
        retryPolicy(domain, props); // an invalid retry setting fails the startup, before any listener is registered
    }

    /**
     * The retry policy of the domain, with the default of {@code maxRetries} resolved: with a DLQ, a failed message
     * is retried {@link #DEFAULT_DLQ_MAX_RETRIES} times; without it, it is retried in place until it succeeds (-1).
     *
     * @throws InvalidConfigurationException when {@code maxRetries} or {@code retryDelay} is out of range
     */
    static RetryPolicy retryPolicy(String domain, AsyncKafkaProps props) {
        int maxRetries = props.getMaxRetries();
        int retryDelay = props.getRetryDelay();
        if (maxRetries < -1) {
            throw new InvalidConfigurationException("Domain " + domain + ": maxRetries must be -1 (the default) or "
                    + "0 or more, but it is " + maxRetries);
        }
        if (retryDelay < 0) {
            throw new InvalidConfigurationException("Domain " + domain + ": retryDelay must be 0 or more "
                    + "milliseconds, but it is " + retryDelay);
        }
        boolean withDLQRetry = props.getWithDLQRetry();
        if (withDLQRetry && maxRetries == -1) {
            maxRetries = DEFAULT_DLQ_MAX_RETRIES;
        }
        return new RetryPolicy(withDLQRetry, maxRetries, retryDelay);
    }

    public String domain() {
        return domain;
    }

    @Override
    public AsyncKafkaProps props() {
        return props;
    }

    public Components components() {
        return components;
    }

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
            listeners.add(eventListener);
            eventListener.startListener(topologyCreator);
        }
    }

    @Override
    public void listenNotificationEvents(HandlerResolver resolver) {
        if (!resolver.getNotificationListeners().isEmpty()) {
            ApplicationNotificationsListener notificationEventListener = new ApplicationNotificationsListener(
                    listenerContext(resolver), props.getAppName());
            listeners.add(notificationEventListener);
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
            listeners.add(topicListener);
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
     * Stops the listeners (closing their consumers), then closes the producer, if it was created, once the records
     * already handed to it are sent, and the admin clients of the domain. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        listeners.forEach(GenericMessageListener::stop);
        listeners.clear();
        sender.close();
        topologyCreator.close();
        healthIndicator.close();
        releaseDiscardProvider.run();
    }

    /**
     * What the listeners of this domain share, with the retry policy and the topology setting of its properties.
     */
    private ListenerContext<ReactiveMessageListener> listenerContext(HandlerResolver resolver) {
        return new ListenerContext<>(receiver, resolver, converter, retryPolicy(domain, props),
                props.getCreateTopology(), discardNotifier, errorReporter);
    }
}
