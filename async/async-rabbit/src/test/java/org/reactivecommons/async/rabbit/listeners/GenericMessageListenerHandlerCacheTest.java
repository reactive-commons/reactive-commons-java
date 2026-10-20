package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The handlers a listener resolves per routing key, and the time a delivery waits for a handler thread.
 */
class GenericMessageListenerHandlerCacheTest {

    private final Receiver receiver = mock(Receiver.class);
    private final Channel channel = mock(Channel.class);
    private final AtomicInteger resolutions = new AtomicInteger();

    /**
     * Wildcard subscriptions can receive an unbounded number of distinct routing keys: the handlers resolved for
     * them must not be kept without limit.
     */
    @Test
    void distinctRoutingKeysDoNotGrowTheHandlerCacheWithoutLimit() throws Exception {
        int routingKeys = 3_000;
        Flux<AcknowledgableDelivery> deliveries = Flux.range(0, routingKeys).map(i -> delivery(i, "event." + i));
        TestListener listener = start(deliveries, new SimpleMeterRegistry());

        verify(channel, timeout(20_000).times(routingKeys)).basicAck(anyLong(), any(Boolean.class));
        int cached = listener.cachedHandlers();
        System.out.println("handlers cached for " + routingKeys + " routing keys: " + cached + ", resolutions: "
                + resolutions.get());

        assertThat(cached).as("bounded cache").isLessThanOrEqualTo(1_000);
    }

    /**
     * A delivery may wait for a free handler thread (the pool is shared by every listener): that wait is measured,
     * since a delivery not acknowledged within the broker's consumer_timeout gets its channel closed.
     */
    @Test
    void theWaitForAHandlerThreadIsMeasured() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        start(Flux.just(delivery(0, "event.0")), registry);

        verify(channel, timeout(5_000)).basicAck(anyLong(), any(Boolean.class));
        Timer wait = registry.find("reactive_commons.consumer.handler.wait").tag("queue", "test-queue").timer();

        assertThat(wait).as("wait timer registered").isNotNull();
        assertThat(wait.count()).isEqualTo(1);
    }

    private TestListener start(Flux<AcknowledgableDelivery> deliveries, SimpleMeterRegistry registry) {
        when(receiver.consumeManualAck(anyString(), any(ConsumeOptions.class)))
                .thenReturn(deliveries.concatWith(Flux.never()));
        ReactiveMessageListener messageListener = new ReactiveMessageListener(receiver, mock(TopologyCreator.class),
                250, 250, new SubscriberRegistry(), registry, "app", null);
        TestListener listener = new TestListener(messageListener, resolutions);
        listener.startListener();
        return listener;
    }

    private AcknowledgableDelivery delivery(long tag, String routingKey) {
        Map<String, Object> headers = new HashMap<>();
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder().messageId("m-" + tag).headers(headers)
                .build();
        Delivery delivery = new Delivery(new Envelope(tag + 1, false, "exchange", routingKey), properties,
                "{}".getBytes());
        return new AcknowledgableDelivery(delivery, channel, (context, error) -> {
            throw new IllegalStateException(error);
        });
    }

    private static class TestListener extends GenericMessageListener {
        private final AtomicInteger resolutions;

        TestListener(ReactiveMessageListener listener, AtomicInteger resolutions) {
            super("test-queue", listener, false, false, -1, 10, mock(DiscardNotifier.class), "event",
                    mock(CustomReporter.class));
            this.resolutions = resolutions;
        }

        int cachedHandlers() throws Exception {
            var field = GenericMessageListener.class.getDeclaredField("handlers");
            field.setAccessible(true);
            return ((Map<?, ?>) field.get(this)).size();
        }

        @Override
        protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
            resolutions.incrementAndGet();
            return message -> Mono.empty();
        }

        @Override
        protected String getExecutorPath(AcknowledgableDelivery msj) {
            return msj.getEnvelope().getRoutingKey();
        }

        @Override
        protected Object parseMessageForReporter(Message msj) {
            return new Object();
        }

        @Override
        protected String getKind() {
            return "test";
        }
    }
}
