package org.reactivecommons.async.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
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
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.ssl.SslBundles;
import reactor.core.publisher.Mono;

@Getter
@AllArgsConstructor
public class KafkaBrokerProvider implements BrokerProvider<AsyncKafkaProps> {
    private final String domain;
    private final AsyncKafkaProps props;
    private final ReactiveReplyRouter router;
    private final KafkaJacksonMessageConverter converter;
    private final MeterRegistry meterRegistry;
    private final CustomReporter errorReporter;
    private final KafkaReactiveHealthIndicator healthIndicator;
    private final ReactiveMessageListener receiver;
    private final ReactiveMessageSender sender;
    private final DiscardNotifier discardNotifier;
    private final TopologyCreator topologyCreator;
    private final KafkaCustomizations customizations;
    private final SslBundles sslBundles;

    @Override
    public DomainEventBus getDomainBus() {
        return new KafkaDomainEventBus(sender);
    }

    @Override
    public DirectAsyncGateway getDirectAsyncGateway(HandlerResolver resolver) {
        return new KafkaDirectAsyncGateway();
    }

    @Override
    public void listenDomainEvents(HandlerResolver resolver) {
        if (!props.getDomain().isIgnoreThisListener() && !resolver.getEventListeners().isEmpty()) {
            ApplicationEventListener eventListener = new ApplicationEventListener(receiver,
                    resolver,
                    converter,
                    props.getWithDLQRetry(),
                    props.getCreateTopology(),
                    props.getMaxRetries(),
                    props.getRetryDelay(),
                    discardNotifier,
                    errorReporter,
                    props.getAppName());
            eventListener.startListener(topologyCreator);
        }
    }

    @Override
    public void listenNotificationEvents(HandlerResolver resolver) {
        if (!resolver.getNotificationListeners().isEmpty()) {
            ApplicationNotificationsListener notificationEventListener = new ApplicationNotificationsListener(receiver,
                    resolver,
                    converter,
                    props.getWithDLQRetry(),
                    props.getCreateTopology(),
                    props.getMaxRetries(),
                    props.getRetryDelay(),
                    discardNotifier,
                    errorReporter,
                    props.getAppName());
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
    public void listenReplies(HandlerResolver resolver) {
        // May be implemented in the future
    }

    @Override
    public Mono<Health> healthCheck() {
        return healthIndicator.health();
    }
}
