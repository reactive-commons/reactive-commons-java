package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.impl.AMQImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.RabbitMessage;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.test.publisher.PublisherProbe;
import reactor.test.publisher.TestPublisher;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.commons.Headers.COMPLETION_ONLY_SIGNAL;
import static org.reactivecommons.async.commons.Headers.CORRELATION_ID;


@ExtendWith(MockitoExtension.class)
class ApplicationReplyListenerTest {

    public static final String REPLY_QUEUE = "reply-queue";
    public static final String ROUTE_KEY = "random-route-key";
    public static final String EXCHANGE = "globalReply";


    @Captor
    private ArgumentCaptor<QueueSpecification> queueCaptor;

    @Captor
    private ArgumentCaptor<ExchangeSpecification> exchangeCaptor;

    @Captor
    private ArgumentCaptor<BindingSpecification> bindingCaptor;

    @Mock
    private Receiver receiver;

    @Mock
    private TopologyCreator topologyCreator;

    @Mock
    private ReactiveReplyRouter reactiveReplyRouter;

    private ApplicationReplyListener applicationReplyListener;


    @BeforeEach
    void setUp() {
        ReactiveMessageListener reactiveMessageListener = new ReactiveMessageListener(receiver, topologyCreator);

        applicationReplyListener = new ApplicationReplyListener(reactiveReplyRouter,
                reactiveMessageListener,
                REPLY_QUEUE);

        when(topologyCreator.declare(queueCaptor.capture()))
                .thenReturn(Mono.just(new AMQImpl.Queue.DeclareOk(REPLY_QUEUE, 0, 0)));

        when(topologyCreator.declare(exchangeCaptor.capture()))
                .thenReturn(Mono.just(new AMQImpl.Exchange.DeclareOk()));

        when(topologyCreator.bind(bindingCaptor.capture()))
                .thenReturn(Mono.just(new AMQImpl.Queue.BindOk()));
    }

    @Test
    void shouldBindExclusiveQueueWithGlobalReplyExchange() {
        instructConsumerMock(Flux.empty(), Flux.never());

        applicationReplyListener.startListening(ROUTE_KEY);

        assertThat(queueCaptor.getValue())
                .extracting(QueueSpecification::getName, QueueSpecification::isDurable,
                        QueueSpecification::isAutoDelete, QueueSpecification::isExclusive)
                .containsExactly(REPLY_QUEUE, false, true, true);

        assertThat(exchangeCaptor.getValue())
                .extracting(ExchangeSpecification::getName, ExchangeSpecification::getType,
                        ExchangeSpecification::isDurable)
                .containsExactly(EXCHANGE, "topic", true);

        assertThat(bindingCaptor.getValue())
                .extracting(BindingSpecification::getExchange, BindingSpecification::getRoutingKey,
                        BindingSpecification::getQueue)
                .containsExactly(EXCHANGE, ROUTE_KEY, REPLY_QUEUE);
    }

    @Test
    void shouldResubscribeIfPreviousSubscriptionCompletes() {
        TestPublisher<Delivery> initialSource = TestPublisher.create();
        PublisherProbe<Delivery> newSource = PublisherProbe.of(Flux.never());

        instructConsumerMock(initialSource.flux(), newSource.flux());

        applicationReplyListener.startListening(ROUTE_KEY);
        initialSource.complete();

        initialSource.assertWasSubscribed();
        newSource.assertWasSubscribed();
    }

    @Test
    void shouldRouteContentfulReply() {
        String correlationId = "1234";

        Map<String, Object> headers = new HashMap<>();
        headers.put(CORRELATION_ID, correlationId);

        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .headers(headers)
                .build();

        Envelope envelope = new Envelope(1234L, true, EXCHANGE, ROUTE_KEY);
        Delivery delivery = new Delivery(envelope, properties, "content".getBytes());

        instructConsumerMock(Flux.just(delivery), Flux.never());

        applicationReplyListener.startListening(ROUTE_KEY);

        verify(reactiveReplyRouter)
                .routeReply(correlationId, RabbitMessage.fromDelivery(delivery));
    }

    @Test
    void shouldRouteEmptyReply() {
        String correlationId = "1234";

        Map<String, Object> headers = new HashMap<>();
        headers.put(CORRELATION_ID, correlationId);
        headers.put(COMPLETION_ONLY_SIGNAL, true);

        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .headers(headers)
                .build();

        Envelope envelope = new Envelope(1234L, true, EXCHANGE, ROUTE_KEY);
        Delivery delivery = new Delivery(envelope, properties, null);

        instructConsumerMock(Flux.just(delivery), Flux.never());

        applicationReplyListener.startListening(ROUTE_KEY);

        verify(reactiveReplyRouter)
                .routeEmpty(correlationId);
    }

    private void instructConsumerMock(Flux<Delivery> initialSource, Flux<Delivery> newSource) {
        AtomicReference<Flux<Delivery>> sourceReference = new AtomicReference<>(initialSource);

        when(receiver.consumeAutoAck(REPLY_QUEUE))
                .thenAnswer(invocation -> Flux.defer(() -> sourceReference.getAndSet(newSource)));
    }
}