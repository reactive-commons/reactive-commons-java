package org.reactivecommons.async.kafka;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import org.reactivecommons.async.kafka.health.KafkaReactiveHealthIndicator;
import org.reactivecommons.async.kafka.listeners.GenericMessageListener;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.test.StepVerifier;

import javax.sound.midi.Receiver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        provider.start();
        // Act
        provider.close();
        provider.close();
        // Assert
        assertThat(consumerCancelled).as("consumer flow cancelled, which closes the Kafka consumer").isTrue();
        verify(sender, times(1)).close();
        verify(creator, times(1)).close();
        // KC-31: the admin client is the one of the topology creator, which closes it
        verify(healthIndicator, never()).close();
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
        brokerProvider.start();
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
        brokerProvider.start();
        // Assert
        verify(listener, times(1)).listen(eq("dummy.consumer-group"), any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldListenNotificationEventsWithoutAConsumerGroup() {
        List mockedListeners = spy(List.of());
        when(mockedListeners.isEmpty()).thenReturn(false);
        when(handlerResolver.getNotificationListeners()).thenReturn(mockedListeners);
        when(handlerResolver.getNotificationNames()).thenReturn(List.of("notification.one"));
        TopicPartition partition = new TopicPartition("notification.one", 0);
        when(creator.createTopics(any())).thenReturn(Mono.empty());
        when(creator.partitionsOf(List.of("notification.one"))).thenReturn(Mono.just(List.of(partition)));
        when(listener.listenAssigned(any(), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenNotificationEvents(handlerResolver);
        brokerProvider.start();
        // Assert
        verify(listener, times(1)).listenAssigned(eq(List.of(partition)), any(), any());
        verify(listener, never()).listen(any(String.class), any(), any());
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
        brokerProvider.start();
        // Assert
        verify(creator, times(1)).createDlqTopics(List.of("event.one", "corruptData"));
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
        when(creator.partitionsOf(any())).thenReturn(Mono.just(List.of()));
        when(listener.listenAssigned(any(), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenNotificationEvents(handlerResolver);
        brokerProvider.start();
        // Assert
        verify(creator, times(1)).createTopics(List.of("notification.one"));
        verify(creator, times(1)).createDlqTopics(List.of("notification.one", "corruptData"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldNotCreateDlqTopicsForNotificationsWhenDlqIsDisabled() {
        List mockedListeners = spy(List.of());
        when(mockedListeners.isEmpty()).thenReturn(false);
        when(handlerResolver.getNotificationListeners()).thenReturn(mockedListeners);
        when(creator.createTopics(any())).thenReturn(Mono.empty());
        when(creator.partitionsOf(any())).thenReturn(Mono.just(List.of()));
        when(listener.listenAssigned(any(), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenNotificationEvents(handlerResolver);
        brokerProvider.start();
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
        brokerProvider.start();
        // Assert
        verify(listener, times(1)).listen(eq("test"), eq(List.of("my.custom.topic")), any());
    }

    /**
     * The topics of a domain share one consumer of its group: a consumer per topic would add a member to the group,
     * a connection and a heartbeat thread per topic, and rebalance every topic when any of them restarts.
     */
    @Test
    void shouldListenEveryRegisteredTopicWithOneConsumer() {
        RegisteredQueueListener first = new RegisteredQueueListener("topic.one",
                message -> Mono.empty(), topologyCreator -> Mono.empty());
        RegisteredQueueListener second = new RegisteredQueueListener("topic.two",
                message -> Mono.empty(), topologyCreator -> Mono.empty());
        when(handlerResolver.getTopicListeners())
                .thenReturn(Map.of("topic.one", first, "topic.two", second));
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenTopics(handlerResolver);
        brokerProvider.start();
        // Assert
        verify(listener, times(1)).listen(eq("test"),
                argThat(topics -> Set.copyOf(topics).equals(Set.of("topic.one", "topic.two"))), any());
    }

    /**
     * The listeners only consume once the provider is started, at the end of the refresh of the application context:
     * a handler never runs while the beans it uses are still being created.
     */
    @Test
    void shouldNotConsumeUntilItIsStarted() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), topologyCreator -> Mono.empty());
        when(handlerResolver.getTopicListeners()).thenReturn(Map.of("my.custom.topic", registeredListener));
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenTopics(handlerResolver);
        // Assert
        verify(listener, never()).listen(any(String.class), any(), any());
        brokerProvider.start();
        brokerProvider.start();
        verify(listener, times(1)).listen(eq("test"), eq(List.of("my.custom.topic")), any());
    }

    @Test
    void shouldStartRightAwayAListenerRegisteredOnceStarted() {
        RegisteredQueueListener registeredListener = new RegisteredQueueListener("my.custom.topic",
                message -> Mono.empty(), topologyCreator -> Mono.empty());
        when(handlerResolver.getTopicListeners()).thenReturn(Map.of("my.custom.topic", registeredListener));
        when(listener.listen(any(String.class), any(), any())).thenReturn(Flux.never());
        brokerProvider.start();
        // Act
        brokerProvider.listenTopics(handlerResolver);
        // Assert
        verify(listener, times(1)).listen(eq("test"), eq(List.of("my.custom.topic")), any());
    }

    /**
     * Closing waits for the messages being handled, but not forever: a handler that never ends (or a broker that does
     * not answer the final commit) cannot hold the shutdown of the application.
     */
    @Test
    void shouldGiveUpWaitingForTheMessagesBeingHandledAfterTheDrainTimeout() {
        GenericMessageListener stuck = mock(GenericMessageListener.class);
        GenericMessageListener drained = mock(GenericMessageListener.class);
        when(stuck.drain()).thenReturn(Mono.never());
        when(drained.drain()).thenReturn(Mono.empty());
        long start = System.nanoTime();
        // Act
        boolean done = KafkaBrokerProvider.drain("accounts", List.of(stuck, drained), Duration.ofMillis(300));
        // Assert
        assertThat(done).as("every listener drained").isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        verify(drained).drain();
    }

    @Test
    void shouldNotWaitWhenNoMessageIsBeingHandled() {
        GenericMessageListener drained = mock(GenericMessageListener.class);
        when(drained.drain()).thenReturn(Mono.empty());
        // Act
        boolean done = KafkaBrokerProvider.drain("accounts", List.of(drained), Duration.ofSeconds(10));
        // Assert
        assertThat(done).isTrue();
    }

    @Test
    void shouldNotListenAnyTopicWhenNoneIsRegistered() {
        when(handlerResolver.getTopicListeners()).thenReturn(Map.of());
        // Act
        brokerProvider.listenTopics(handlerResolver);
        brokerProvider.start();
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
        brokerProvider.start();
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

    @ParameterizedTest(name = "withDLQRetry={0}, maxRetries={1} -> retried {2} times")
    @CsvSource({
            "false, -1, -1", // retried in place until it succeeds
            "false, 3, 3",
            "true, -1, 10", // the documented default with a DLQ
            "true, 3, 3",
            "true, 0, 0"
    })
    void shouldResolveTheRetryPolicyOfTheDomain(boolean withDLQRetry, int maxRetries, long expectedRetries) {
        AsyncKafkaProps domainProps = AsyncKafkaProps.builder().withDLQRetry(withDLQRetry).maxRetries(maxRetries)
                .retryDelay(500).build();

        RetryPolicy policy = KafkaBrokerProvider.retryPolicy("accounts", domainProps);

        assertThat(policy).isEqualTo(new RetryPolicy(withDLQRetry, expectedRetries, 500));
    }

    @Test
    void shouldRejectANegativeMaxRetriesOtherThanTheDefault() {
        AsyncKafkaProps domainProps = AsyncKafkaProps.builder().maxRetries(-2).build();

        assertThatThrownBy(() -> KafkaBrokerProvider.retryPolicy("accounts", domainProps))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("accounts").hasMessageContaining("maxRetries");
    }

    @Test
    void shouldRejectANegativeRetryDelay() {
        AsyncKafkaProps domainProps = AsyncKafkaProps.builder().retryDelay(-1).build();

        assertThatThrownBy(() -> KafkaBrokerProvider.retryPolicy("accounts", domainProps))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("accounts").hasMessageContaining("retryDelay");
    }
}
