package org.reactivecommons.async.rabbit.config;

import com.rabbitmq.client.AMQP;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.listeners.ApplicationNotificationListener;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

class NotificationListenersConfigTest {

    private final AsyncProps props = new AsyncProps();
    private final AsyncPropsDomain asyncPropsDomain = AsyncPropsDomain.builder()
            .withDefaultAppName("appName")
            .withDefaultRabbitProperties(new RabbitProperties())
            .withDomain(DEFAULT_DOMAIN, props)
            .build();
    private final NotificationListenersConfig config = new NotificationListenersConfig(asyncPropsDomain);
    private final ReactiveMessageListener listener = mock(ReactiveMessageListener.class);
    private final TopologyCreator creator = mock(TopologyCreator.class);
    private final HandlerResolver handlerResolver = mock(HandlerResolver.class);
    private final MessageConverter messageConverter = mock(MessageConverter.class);
    private final DiscardNotifier discardNotifier = mock(DiscardNotifier.class);
    private final CustomReporter customReporter = mock(CustomReporter.class);
    private final Receiver receiver = mock(Receiver.class);
    private final ConnectionManager manager = new ConnectionManager();
    private final DomainHandlers handlers = new DomainHandlers();

    @BeforeEach
    public void init() {
        when(handlerResolver.getEventListeners()).thenReturn(Collections.emptyList());
        when(creator.bind(any(BindingSpecification.class))).thenReturn(Mono.just(mock(AMQP.Queue.BindOk.class)));
        when(creator.declare(any(ExchangeSpecification.class))).thenReturn(Mono.just(mock(AMQP.Exchange.DeclareOk.class)));
        when(creator.declare(any(QueueSpecification.class))).thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        when(creator.declareDLQ(any(String.class), any(String.class), any(Integer.class), any())).thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        when(creator.declareQueue(any(String.class), any())).thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        when(creator.declareQueue(any(String.class), any(String.class), any())).thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        when(listener.getTopologyCreator()).thenReturn(creator);
        when(receiver.consumeManualAck(any(String.class), any(ConsumeOptions.class))).thenReturn(Flux.never());
        when(listener.getReceiver()).thenReturn(receiver);
        when(listener.getMaxConcurrency()).thenReturn(20);
        manager.addDomain(DEFAULT_DOMAIN, listener, null, null);
        handlers.add(DEFAULT_DOMAIN, handlerResolver);
    }

    @Test
    void eventNotificationListener() {
        final ApplicationNotificationListener applicationEventListener =
                config.eventNotificationListener(manager, handlers, messageConverter, customReporter);
        Assertions.assertThat(applicationEventListener).isNotNull();
    }
}
