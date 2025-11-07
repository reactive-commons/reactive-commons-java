package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.AMQP;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.rabbit.health.RabbitReactiveHealthIndicator;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.config.health.RCHealth;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@ExtendWith(MockitoExtension.class)
class RabbitMQBrokerProviderTest {
    private final AsyncProps props = new AsyncProps();
    private final BrokerConfig brokerConfig = new BrokerConfig();
    @Mock
    private ReactiveMessageListener listener;
    @Mock
    private TopologyCreator creator;
    @Mock
    private HandlerResolver handlerResolver;
    @Mock
    private RabbitJacksonMessageConverter messageConverter;
    @Mock
    private CustomReporter customReporter;
    @Mock
    private Receiver receiver;
    @Mock
    private ReactiveReplyRouter router;
    @Mock
    private MeterRegistry meterRegistry;
    @Mock
    private ReactiveMessageSender sender;
    @Mock
    private DiscardNotifier discardNotifier;
    @Mock
    private RabbitReactiveHealthIndicator healthIndicator;


    private BrokerProvider<AsyncProps> brokerProvider;


    @BeforeEach
    void init() {
        IBrokerConfigProps configProps = new BrokerConfigProps(props);
        props.setBrokerConfigProps(configProps);
        props.setAppName("test");
        props.setListenReplies(Boolean.TRUE);
        brokerProvider = new RabbitMQBrokerProvider(DEFAULT_DOMAIN,
                props,
                brokerConfig,
                router,
                messageConverter,
                meterRegistry,
                customReporter,
                healthIndicator,
                listener,
                sender,
                discardNotifier);
    }

    @Test
    void shouldCreateDomainEventBus() {
        when(sender.getTopologyCreator()).thenReturn(creator);
        when(creator.declare(any(ExchangeSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Exchange.DeclareOk.class)));
        // Act
        DomainEventBus domainBus = brokerProvider.getDomainBus();
        // Assert
        assertThat(domainBus).isExactlyInstanceOf(RabbitDomainEventBus.class);
    }

    @Test
    void shouldCreateDirectAsyncGateway() {
        when(sender.getTopologyCreator()).thenReturn(creator);
        when(listener.getTopologyCreator()).thenReturn(creator);
        when(creator.declare(any(ExchangeSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Exchange.DeclareOk.class)));
        when(creator.bind(any(BindingSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Queue.BindOk.class)));
        when(creator.declare(any(QueueSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        when(listener.getReceiver()).thenReturn(receiver);
        when(receiver.consumeAutoAck(any(String.class), any(ConsumeOptions.class))).thenReturn(Flux.never());
        // Act
        DirectAsyncGateway domainBus = brokerProvider.getDirectAsyncGateway();
        // Assert
        assertThat(domainBus).isExactlyInstanceOf(RabbitDirectAsyncGateway.class);
    }

    @Test
    void shouldListenDomainEvents() {
        when(listener.getTopologyCreator()).thenReturn(creator);
        when(creator.declare(any(ExchangeSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Exchange.DeclareOk.class)));
        when(creator.declareQueue(any(String.class), any())).thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        when(listener.getReceiver()).thenReturn(receiver);
        when(listener.getMaxConcurrency()).thenReturn(1);
        when(receiver.consumeManualAck(any(String.class), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenDomainEvents(handlerResolver);
        // Assert
        verify(receiver, times(1)).consumeManualAck(any(String.class), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldListenNotificationEvents() {
        when(handlerResolver.hasNotificationListeners()).thenReturn(true);
        when(listener.getTopologyCreator()).thenReturn(creator);
        when(creator.declare(any(ExchangeSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Exchange.DeclareOk.class)));
        when(creator.declare(any(QueueSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        when(listener.getReceiver()).thenReturn(receiver);
        when(listener.getMaxConcurrency()).thenReturn(1);
        when(receiver.consumeManualAck(any(String.class), any())).thenReturn(Flux.never());
        List mockedListeners = spy(List.of());
        when(handlerResolver.getNotificationListeners()).thenReturn(mockedListeners);
        // Act
        brokerProvider.listenNotificationEvents(handlerResolver);
        // Assert
        verify(receiver, times(1)).consumeManualAck(any(String.class), any());
    }

    @Test
    void shouldListenCommands() {
        when(handlerResolver.hasCommandHandlers()).thenReturn(true);
        when(listener.getTopologyCreator()).thenReturn(creator);
        when(creator.declare(any(ExchangeSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Exchange.DeclareOk.class)));
        when(creator.declareQueue(any(String.class), any()))
                .thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        when(creator.bind(any(BindingSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Queue.BindOk.class)));
        when(listener.getReceiver()).thenReturn(receiver);
        when(listener.getMaxConcurrency()).thenReturn(1);
        when(receiver.consumeManualAck(any(String.class), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenCommands(handlerResolver);
        // Assert
        verify(receiver, times(1)).consumeManualAck(any(String.class), any());
    }

    @Test
    void shouldListenQueries() {
        when(handlerResolver.hasQueryHandlers()).thenReturn(true);
        when(listener.getTopologyCreator()).thenReturn(creator);
        when(creator.declare(any(ExchangeSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Exchange.DeclareOk.class)));
        when(creator.declareQueue(any(String.class), any()))
                .thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        when(creator.bind(any(BindingSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Queue.BindOk.class)));
        when(listener.getReceiver()).thenReturn(receiver);
        when(listener.getMaxConcurrency()).thenReturn(1);
        when(receiver.consumeManualAck(any(String.class), any())).thenReturn(Flux.never());
        // Act
        brokerProvider.listenQueries(handlerResolver);
        // Assert
        verify(receiver, times(1)).consumeManualAck(any(String.class), any());
    }

    @Test
    void shouldListenQueuesWithSingleQueue() {
        RegisteredQueueListener queueListener = mock(RegisteredQueueListener.class);
        when(queueListener.queueName()).thenReturn("test.queue");
        when(queueListener.topologyHandlerSetup()).thenReturn(topologyCreator -> Mono.empty());

        when(listener.getTopologyCreator()).thenReturn(creator);
        when(listener.getReceiver()).thenReturn(receiver);
        when(listener.getMaxConcurrency()).thenReturn(1);
        when(receiver.consumeManualAck(any(String.class), any())).thenReturn(Flux.never());
        when(handlerResolver.getQueueListeners()).thenReturn(Map.of("test.queue", queueListener));

        brokerProvider.listenQueues(handlerResolver);

        verify(receiver, times(1)).consumeManualAck(any(String.class), any());
    }

    @Test
    void shouldListenQueuesWithMultipleQueues() {
        RegisteredQueueListener queueListener1 = mock(RegisteredQueueListener.class);
        RegisteredQueueListener queueListener2 = mock(RegisteredQueueListener.class);
        RegisteredQueueListener queueListener3 = mock(RegisteredQueueListener.class);

        when(queueListener1.queueName()).thenReturn("test.queue1");
        when(queueListener1.topologyHandlerSetup()).thenReturn(topologyCreator -> Mono.empty());
        when(queueListener2.queueName()).thenReturn("test.queue2");
        when(queueListener2.topologyHandlerSetup()).thenReturn(topologyCreator -> Mono.empty());
        when(queueListener3.queueName()).thenReturn("test.queue3");
        when(queueListener3.topologyHandlerSetup()).thenReturn(topologyCreator -> Mono.empty());

        when(listener.getTopologyCreator()).thenReturn(creator);
        when(listener.getReceiver()).thenReturn(receiver);
        when(listener.getMaxConcurrency()).thenReturn(1);
        when(receiver.consumeManualAck(any(String.class), any())).thenReturn(Flux.never());
        when(handlerResolver.getQueueListeners()).thenReturn(
                Map.of(
                        "test.queue1", queueListener1,
                        "test.queue2", queueListener2,
                        "test.queue3", queueListener3
                )
        );

        brokerProvider.listenQueues(handlerResolver);

        verify(receiver, times(3)).consumeManualAck(any(String.class), any());
    }

    @Test
    void shouldNotListenQueuesWhenNoQueuesRegistered() {
        when(handlerResolver.getQueueListeners()).thenReturn(java.util.Map.of());

        brokerProvider.listenQueues(handlerResolver);

        verify(receiver, times(0)).consumeManualAck(any(String.class), any());
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
}
