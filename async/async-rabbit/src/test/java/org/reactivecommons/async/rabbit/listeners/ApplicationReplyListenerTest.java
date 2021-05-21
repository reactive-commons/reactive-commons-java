package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.impl.AMQImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
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

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class ApplicationReplyListenerTest {

    public static final String REPLY_QUEUE = "reply-queue";
    public static final String ROUTE_KEY = "random-route-key";

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
        final String exchange = "globalReply";

        AtomicReference<Flux<Delivery>> sourceReference = new AtomicReference<>(Flux.empty());

        when(receiver.consumeAutoAck(REPLY_QUEUE))
                .thenAnswer(invocation -> Flux.defer(() -> sourceReference.getAndSet(Flux.never())));

        applicationReplyListener.startListening(ROUTE_KEY);

        assertThat(queueCaptor.getValue())
                .extracting(QueueSpecification::getName, QueueSpecification::isDurable,
                        QueueSpecification::isAutoDelete, QueueSpecification::isExclusive)
                .containsExactly(REPLY_QUEUE, false, true, true);

        assertThat(exchangeCaptor.getValue())
                .extracting(ExchangeSpecification::getName, ExchangeSpecification::getType,
                        ExchangeSpecification::isDurable)
                .containsExactly(exchange, "topic", true);

        assertThat(bindingCaptor.getValue())
                .extracting(BindingSpecification::getExchange, BindingSpecification::getRoutingKey,
                        BindingSpecification::getQueue)
                .containsExactly(exchange, ROUTE_KEY, REPLY_QUEUE);
    }

    @Test
    void shouldResubscribeIfPreviousSubscriptionCompletes() {
        TestPublisher<Delivery> initialSource = TestPublisher.create();
        PublisherProbe<Delivery> newSource = PublisherProbe.of(Flux.never());

        AtomicReference<Flux<Delivery>> sourceReference = new AtomicReference<>(initialSource.flux());

        when(receiver.consumeAutoAck(REPLY_QUEUE))
                .thenAnswer(invocation -> Flux.defer(() -> sourceReference.getAndSet(newSource.flux())));

        applicationReplyListener.startListening(ROUTE_KEY);
        initialSource.complete();

        initialSource.assertWasSubscribed();
        newSource.assertWasSubscribed();
    }
}