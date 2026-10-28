package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Receiver;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bindings of a listener are declared one after the other: they all go through the single channel of its topology
 * creator, on which the client blocks the thread that sends an RPC while another one is in progress, so declaring
 * them at the same time only takes a thread per binding without being any faster.
 */
class ListenerBindingsTest {

    private static final int EVENTS = 5;

    private final TopologyCreator creator = mock(TopologyCreator.class);
    private final AtomicInteger bindingsInProgress = new AtomicInteger();
    private final AtomicInteger mostBindingsAtOnce = new AtomicInteger();

    @BeforeEach
    void stubTheDeclarations() {
        when(creator.declare(any(ExchangeSpecification.class)))
                .thenReturn(Mono.just(mock(AMQP.Exchange.DeclareOk.class)));
        when(creator.declare(any(QueueSpecification.class))).thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        when(creator.declareQueue(anyString(), any())).thenReturn(Mono.just(mock(AMQP.Queue.DeclareOk.class)));
        AMQP.Queue.BindOk bindOk = mock(AMQP.Queue.BindOk.class);
        when(creator.bind(any(BindingSpecification.class))).thenAnswer(invocation -> Mono.defer(() -> {
            mostBindingsAtOnce.accumulateAndGet(bindingsInProgress.incrementAndGet(), Math::max);
            // over once the broker answers, before the next one is sent (doFinally would run after it)
            return Mono.delay(Duration.ofMillis(20)).thenReturn(bindOk)
                    .doOnTerminate(bindingsInProgress::decrementAndGet);
        }));
    }

    @Test
    void theEventBindingsAreDeclaredOneAfterTheOther() {
        ApplicationEventListener listener = new ApplicationEventListener(messageListener(), "app.subsEvents",
                "domainEvents", resolver(), mock(MessageConverter.class), false, true, -1, 1000, Optional.empty(),
                mock(DiscardNotifier.class), mock(CustomReporter.class), "app");

        StepVerifier.create(listener.setUpBindings(creator)).verifyComplete();

        verify(creator, times(EVENTS)).bind(any(BindingSpecification.class));
        assertThat(mostBindingsAtOnce).as("bindings in progress at the same time").hasValue(1);
    }

    @Test
    void theNotificationBindingsAreDeclaredOneAfterTheOther() {
        ApplicationNotificationListener listener = new ApplicationNotificationListener(messageListener(),
                "domainEvents", "app.notification", true, resolver(), mock(MessageConverter.class),
                mock(DiscardNotifier.class), mock(CustomReporter.class));

        StepVerifier.create(listener.setUpBindings(creator)).verifyComplete();

        verify(creator, times(EVENTS)).bind(any(BindingSpecification.class));
        assertThat(mostBindingsAtOnce).as("bindings in progress at the same time").hasValue(1);
    }

    private ReactiveMessageListener messageListener() {
        return new ReactiveMessageListener(mock(Receiver.class), creator);
    }

    private static HandlerResolver resolver() {
        Map<String, RegisteredEventListener<?, ?>> events = new HashMap<>();
        for (int i = 0; i < EVENTS; i++) {
            events.put("event." + i, new RegisteredEventListener<>("event." + i, message -> Mono.empty(),
                    String.class));
        }
        return new HandlerResolver(Map.of(), events, events, events, Map.of(), Map.of(), Map.of());
    }
}
