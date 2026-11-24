package org.reactivecommons.async.kafka;

import lombok.extern.slf4j.Slf4j;
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
import org.reactivecommons.async.kafka.listeners.KafkaListenerSchedulers;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The publisher and the listeners of a Kafka domain. The {@code listen*} methods register the listeners, which start
 * consuming once the provider is started ({@link #start()}). Closing it lets the messages being handled finish, then
 * stops the listeners and closes the producer, the admin client and the threads of the domain.
 */
@Slf4j
public final class KafkaBrokerProvider implements BrokerProvider<AsyncKafkaProps> {
    /**
     * How many times a failed message is retried before it is sent to its DLQ, when {@code withDLQRetry} is true and
     * {@code maxRetries} is left to its default (-1).
     */
    public static final int DEFAULT_DLQ_MAX_RETRIES = 10;
    /**
     * How long closing the provider waits for the messages being handled by its listeners to finish and be committed
     * (the counterpart of the {@code shutdownTimeout} of the Spring for Apache Kafka listener containers), the same as
     * the RabbitMQ providers.
     */
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(10);

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
    private final KafkaListenerSchedulers schedulers;
    private final List<GenericMessageListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean started; // guarded by this

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
     * @param releaseDiscardProvider run once the provider is closed, to release what it shares with other components:
     *                               the shared discard provider it uses, the metrics of the domain
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
        // Threads are only created when the listeners need them
        this.schedulers = KafkaListenerSchedulers.create("kafka-listeners-" + domain);
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
            register(new ApplicationEventListener(listenerContext(resolver), props.resolveEventsGroupId(),
                    schedulers));
        }
    }

    @Override
    public void listenNotificationEvents(HandlerResolver resolver) {
        if (!resolver.getNotificationListeners().isEmpty()) {
            register(new ApplicationNotificationsListener(listenerContext(resolver), schedulers));
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

    /**
     * Registers one listener for every raw topic of the domain: the topics share one consumer of the group.
     */
    @Override
    public void listenTopics(HandlerResolver resolver) {
        if (!resolver.getTopicListeners().isEmpty()) {
            register(new ApplicationTopicListener(listenerContext(resolver), resolver.getTopicListeners().values(),
                    props.resolveTopicListenerGroupId(), sender, schedulers));
        }
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
     * Starts the listener now if the provider was started, otherwise once it is (see {@link #start()}).
     */
    private synchronized void register(GenericMessageListener listener) {
        listeners.add(listener);
        healthIndicator.watch(listener);
        if (started && !closed.get()) {
            listener.startListener(topologyCreator);
        }
    }

    /**
     * Starts consuming with the listeners registered so far. Called once the application context has created and
     * initialized every bean: a handler never runs while the context is being refreshed (e.g. before a bean warms up a
     * cache, or when the startup fails afterwards). A listener registered afterwards starts right away. Idempotent.
     */
    @Override
    public synchronized void start() {
        if (!started && !closed.get()) {
            started = true;
            listeners.forEach(listener -> listener.startListener(topologyCreator));
        }
    }

    /**
     * Lets the messages being handled finish and be committed, up to {@link #DRAIN_TIMEOUT}, so they are not
     * delivered again after the shutdown; the producer stays open meanwhile for the handlers and the DLQ. Then stops
     * the listeners (closing their consumers), closes the producer, if it was created, once the records already
     * handed to it are sent, the admin client (shared by the topology creator and the health check, and with the
     * shared discard notifier for the default domain: it is closed once all of them are) and the threads of the
     * domain. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        drain(domain, listeners, DRAIN_TIMEOUT);
        listeners.forEach(GenericMessageListener::stop);
        listeners.clear();
        sender.close();
        topologyCreator.close();
        schedulers.dispose();
        releaseDiscardProvider.run();
    }

    /**
     * Waits, at most {@code timeout}, until the listeners handled and committed the messages they were handling. The
     * listeners stop consuming at once, all at the same time.
     *
     * @return whether every listener drained in time
     */
    static boolean drain(String domain, List<GenericMessageListener> listeners, Duration timeout) {
        if (listeners.isEmpty()) {
            return true;
        }
        // The only wait of the provider: the application context closes once its providers are closed
        Boolean drained = Flux.fromIterable(listeners)
                .flatMap(GenericMessageListener::drain)
                .then(Mono.just(true))
                .timeout(timeout, Mono.just(false))
                .block(timeout.plusSeconds(1));
        if (!Boolean.TRUE.equals(drained)) {
            log.warn("The messages being handled by the listeners of domain {} did not finish within {}: they are "
                    + "delivered again after the restart", domain, timeout);
            return false;
        }
        return true;
    }

    /**
     * What the listeners of this domain share, with the retry policy and the topology setting of its properties.
     */
    private ListenerContext<ReactiveMessageListener> listenerContext(HandlerResolver resolver) {
        return new ListenerContext<>(receiver, resolver, converter, retryPolicy(domain, props),
                props.getCreateTopology(), discardNotifier, errorReporter);
    }
}
