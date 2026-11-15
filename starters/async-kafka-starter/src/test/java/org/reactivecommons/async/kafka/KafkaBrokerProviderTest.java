package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.kafka.health.KafkaReactiveHealthIndicator;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.health.RCHealth;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.test.StepVerifier;

import javax.sound.midi.Receiver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@ExtendWith(MockitoExtension.class)
class KafkaBrokerProviderTest {
    private final AsyncKafkaProps props = new AsyncKafkaProps();
    @Mock
    private ReactiveMessageListener listener;
    @Mock
    private TopologyCreator creator;
    @Mock
    private HandlerResolver handlerResolver;
    @Mock
    private KafkaJacksonMessageConverter messageConverter;
    @Mock
    private CustomReporter customReporter;
    @Mock
    private Receiver receiver;
    @Mock
    private ReactiveMessageSender sender;
    @Mock
    private DiscardNotifier discardNotifier;
    @Mock
    private KafkaReactiveHealthIndicator healthIndicator;


    private BrokerProvider<AsyncKafkaProps> brokerProvider;


    @BeforeEach
    void init() {
        props.setAppName("test");
        brokerProvider = new KafkaBrokerProvider(DEFAULT_DOMAIN, props, components());
    }

    @Test
    void shouldStopTheListenersAndCloseTheClientsOnceWhenClosed() {
        AtomicBoolean consumerCancelled = new AtomicBoolean();
        AtomicInteger releases = new AtomicInteger();
        KafkaBrokerProvider provider = new KafkaBrokerProvider(DEFAULT_DOMAIN, props, components(),
                releases::incrementAndGet);
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), topologyCreator -> Mono.empty());
        when(handlerResolver.getTopicListeners()).thenReturn(Map.of("my.custom.topic", registeredListener));
        when(listener.listen(any(String.class), any(), any()))
                .thenReturn(Flux.<ReceiverRecord<String, byte[]>>never().doOnCancel(() -> consumerCancelled.set(true)));
        provider.listenTopics(handlerResolver);
        // Act
        provider.close();
        provider.close();
        // Assert
        assertThat(consumerCancelled).as("consumer flow cancelled, which closes the Kafka consumer").isTrue();
        verify(sender, times(1)).close();
        verify(creator, times(1)).close();
        verify(healthIndicator, times(1)).close();
        assertThat(releases).as("shared discard provider released").hasValue(1);
    }

    @Test
    void shouldCreateDomainEventBus() {
        // Act
        DomainEventBus domainBus = brokerProvider.getDomainBus();
        // Assert
        assertThat(domainBus).isExactlyInstanceOf(KafkaDomainEventBus.class);
    }

    @Test
    void shouldCreateDirectAsyncGateway() {
        // Act
        DirectAsyncGateway domainBus = brokerProvider.getDirectAsyncGateway();
        // Assert
        assertThat(domainBus).isExactlyInstanceOf(KafkaDirectAsyncGateway.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldListenDomainEvents() {
        List mockedListeners = spy(List.of());
        when(mockedListeners.isEmpty()).thenReturn(false);
        when(handlerResolver.getEventListeners()).thenReturn(mockedListeners);
        when(creator.createTopics(any())).thenReturn(Mono.empty());
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenDomainEvents(handlerResolver);
        // Assert
        verify(listener, times(1)).listen(eq("test-events"), any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldListenDomainEventsWithExplicitGroupIdFromConnectionProperties() {
        props.getConnectionProperties().getConsumer().setGroupId("dummy.consumer-group");
        List mockedListeners = spy(List.of());
        when(mockedListeners.isEmpty()).thenReturn(false);
        when(handlerResolver.getEventListeners()).thenReturn(mockedListeners);
        when(creator.createTopics(any())).thenReturn(Mono.empty());
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenDomainEvents(handlerResolver);
        // Assert
        verify(listener, times(1)).listen(eq("dummy.consumer-group"), any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldListenNotificationEvents() {
        List mockedListeners = spy(List.of());
        when(mockedListeners.isEmpty()).thenReturn(false);
        when(handlerResolver.getNotificationListeners()).thenReturn(mockedListeners);
        when(creator.createTopics(any())).thenReturn(Mono.empty());
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenNotificationEvents(handlerResolver);
        // Assert
        verify(listener, times(1)).listen(any(String.class), any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldCreateDlqTopicsForDomainEventsWhenDlqIsEnabled() {
        props.setWithDLQRetry(true);
        List mockedListeners = spy(List.of());
        when(mockedListeners.isEmpty()).thenReturn(false);
        when(handlerResolver.getEventListeners()).thenReturn(mockedListeners);
        when(handlerResolver.getEventNames()).thenReturn(List.of("event.one"));
        when(creator.createTopics(any())).thenReturn(Mono.empty());
        when(creator.createDlqTopics(any())).thenReturn(Mono.empty());
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenDomainEvents(handlerResolver);
        // Assert
        verify(creator, times(1)).createDlqTopics(List.of("event.one"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldCreateDlqTopicsForNotificationsWhenDlqIsEnabled() {
        // The DiscardNotifier publishes exhausted notifications to <name>.dlq, so the topic must exist
        props.setWithDLQRetry(true);
        List mockedListeners = spy(List.of());
        when(mockedListeners.isEmpty()).thenReturn(false);
        when(handlerResolver.getNotificationListeners()).thenReturn(mockedListeners);
        when(handlerResolver.getNotificationNames()).thenReturn(List.of("notification.one"));
        when(creator.createTopics(any())).thenReturn(Mono.empty());
        when(creator.createDlqTopics(any())).thenReturn(Mono.empty());
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenNotificationEvents(handlerResolver);
        // Assert
        verify(creator, times(1)).createTopics(List.of("notification.one"));
        verify(creator, times(1)).createDlqTopics(List.of("notification.one"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldNotCreateDlqTopicsForNotificationsWhenDlqIsDisabled() {
        List mockedListeners = spy(List.of());
        when(mockedListeners.isEmpty()).thenReturn(false);
        when(handlerResolver.getNotificationListeners()).thenReturn(mockedListeners);
        when(creator.createTopics(any())).thenReturn(Mono.empty());
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenNotificationEvents(handlerResolver);
        // Assert
        verify(creator, never()).createDlqTopics(any());
    }

    @Test
    void shouldListenTopics() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), topologyCreator -> Mono.empty());
        when(handlerResolver.getTopicListeners())
                .thenReturn(Map.of("my.custom.topic", registeredListener));
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenTopics(handlerResolver);
        // Assert
        verify(listener, times(1)).listen(eq("test"), eq(List.of("my.custom.topic")), any());
    }

    @Test
    void shouldListenEveryRegisteredTopic() {
        RegisteredQueueListener first = new RegisteredQueueListener("topic.one",
                message -> Mono.empty(), topologyCreator -> Mono.empty());
        RegisteredQueueListener second = new RegisteredQueueListener("topic.two",
                message -> Mono.empty(), topologyCreator -> Mono.empty());
        when(handlerResolver.getTopicListeners())
                .thenReturn(Map.of("topic.one", first, "topic.two", second));
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenTopics(handlerResolver);
        // Assert
        verify(listener, times(1)).listen(eq("test"), eq(List.of("topic.one")), any());
        verify(listener, times(1)).listen(eq("test"), eq(List.of("topic.two")), any());
    }

    @Test
    void shouldNotListenAnyTopicWhenNoneIsRegistered() {
        when(handlerResolver.getTopicListeners()).thenReturn(Map.of());
        // Act
        brokerProvider.listenTopics(handlerResolver);
        // Assert
        verify(listener, never()).listen(any(String.class), any(), any());
    }

    @Test
    void shouldUseExplicitGroupIdFromConnectionPropertiesForTopicListeners() {
        props.getConnectionProperties().getConsumer().setGroupId("dummy.consumer-group");
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), topologyCreator -> Mono.empty());
        when(handlerResolver.getTopicListeners())
                .thenReturn(Map.of("my.custom.topic", registeredListener));
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenTopics(handlerResolver);
        // Assert
        verify(listener, times(1))
                .listen(eq("dummy.consumer-group"), eq(List.of("my.custom.topic")), any());
    }

    @Test
    void shouldNotListenQueuesBecauseKafkaHasNoQueueConcept() {
        // Act
        brokerProvider.listenQueues(handlerResolver);
        // Assert
        verify(handlerResolver, never()).getQueueListeners();
        verify(listener, never()).listen(any(String.class), any(), any());
    }

    @Test
    void shouldProxyHealthCheck() {
        when(healthIndicator.health()).thenReturn(Mono.fromSupplier(() -> RCHealth.builder().up().build()));
        // Act
        Mono<RCHealth> flow = brokerProvider.healthCheck();
        // Assert
        StepVerifier.create(flow)
                .expectNextMatches(health -> health.status().equals(RCHealth.Status.UP))
                .verifyComplete();
    }

    private KafkaBrokerProvider.Components components() {
        return new KafkaBrokerProvider.Components(messageConverter, customReporter, healthIndicator, listener, sender,
                discardNotifier, creator);
    }
}
