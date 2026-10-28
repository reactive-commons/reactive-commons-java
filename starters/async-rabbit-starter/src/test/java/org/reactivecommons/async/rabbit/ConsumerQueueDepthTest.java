package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.impl.AMQImpl;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.rabbit.listeners.SubscriberStatus;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsumerQueueDepthTest {

    private static final String QUEUE = "test-queue";
    private static final String DOMAIN = "app";

    @Test
    void shouldExportTheDepthOfTheConsumedQueues() throws Exception {
        AMQP.Queue.DeclareOk declareOk = mock(AMQP.Queue.DeclareOk.class);
        when(declareOk.getMessageCount()).thenReturn(7);
        Channel channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(true);
        when(channel.queueDeclarePassive(QUEUE)).thenReturn(declareOk);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        try (ConsumerQueueDepth ignored = poller(registry, channel)) {
            assertThat(eventually(() -> depth(registry) == 7.0)).as("the gauge reads the broker's count").isTrue();
        }

        assertThat(registry.find("reactive_commons.consumer.queue.depth").gauge())
                .as("the gauge is removed on close").isNull();
    }

    @Test
    void shouldReadNanWhenTheQueueCannotBeQueried() throws Exception {
        Channel channel = mock(Channel.class);
        when(channel.isOpen()).thenReturn(true);
        when(channel.queueDeclarePassive(QUEUE)).thenThrow(new IOException("channel closed"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        try (ConsumerQueueDepth ignored = poller(registry, channel)) {
            assertThat(eventually(() -> {
                Gauge gauge = registry.find("reactive_commons.consumer.queue.depth").gauge();
                return gauge != null && Double.isNaN(gauge.value());
            })).as("an unknown depth reads NaN").isTrue();
        }
    }

    @Test
    void aQueueThatCannotBeQueriedDoesNotHideTheDepthOfTheOthers() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isOpen()).thenReturn(true);
        // a fresh channel each time: the broker closes the channel of a failed passive declare
        when(connection.createChannel()).thenAnswer(invocation -> brokerChannel(Map.of(QUEUE, 7)));
        Map<String, SubscriberStatus> queues = new LinkedHashMap<>();
        queues.put("deleted-queue", SubscriberStatus.healthy("deleted-queue")); // polled first
        queues.put(QUEUE, SubscriberStatus.healthy(QUEUE));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        try (ConsumerQueueDepth ignored = new ConsumerQueueDepth(registry, DOMAIN, Mono.just(connection),
                () -> queues, Duration.ofMillis(20))) {
            assertThat(eventually(() -> depth(registry) == 7.0)).as("read on a new channel").isTrue();
            assertThat(registry.find("reactive_commons.consumer.queue.depth").tag("queue", "deleted-queue")
                    .gauge().value()).isNaN();
        }
    }

    @Test
    void theDepthReadsNanWhileTheConnectionIsLost() throws Exception {
        Connection connection = mock(Connection.class);
        AtomicBoolean open = new AtomicBoolean(true);
        when(connection.isOpen()).thenAnswer(invocation -> open.get());
        when(connection.createChannel()).thenAnswer(invocation -> brokerChannel(Map.of(QUEUE, 7)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        try (ConsumerQueueDepth ignored = new ConsumerQueueDepth(registry, DOMAIN, Mono.just(connection),
                () -> Map.of(QUEUE, SubscriberStatus.healthy(QUEUE)), Duration.ofMillis(20))) {
            assertThat(eventually(() -> depth(registry) == 7.0)).isTrue();
            open.set(false);

            assertThat(eventually(() -> Double.isNaN(depth(registry)))).as("a stale depth would hide a backlog").isTrue();
        }
    }

    @Test
    void anApplicationWithoutListenersNeverRequestsTheListenerConnection() {
        AtomicBoolean requested = new AtomicBoolean();
        Mono<Connection> connection = Mono.<Connection>never().doOnSubscribe(subscription -> requested.set(true));

        try (ConsumerQueueDepth ignored = new ConsumerQueueDepth(new SimpleMeterRegistry(), DOMAIN, connection,
                Map::of, Duration.ofMillis(20))) {
            // several polls without consumed queues
            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).untilFalse(requested);
        }

        assertThat(requested).as("the poller must not open a connection of its own").isFalse();
    }

    /**
     * A channel that answers the passive declares like the broker: the count of an existing queue, and for an
     * unknown one a 404 that closes the channel, so every later call on it fails too.
     */
    private static Channel brokerChannel(Map<String, Integer> depths) throws IOException {
        Channel channel = mock(Channel.class);
        AtomicBoolean open = new AtomicBoolean(true);
        when(channel.isOpen()).thenAnswer(invocation -> open.get());
        when(channel.queueDeclarePassive(anyString())).thenAnswer(invocation -> {
            String queue = invocation.getArgument(0);
            if (!open.get()) {
                throw new IOException("channel is already closed");
            }
            if (!depths.containsKey(queue)) {
                open.set(false);
                throw new IOException("NOT_FOUND - no queue '" + queue + "'");
            }
            return new AMQImpl.Queue.DeclareOk(queue, depths.get(queue), 1);
        });
        return channel;
    }

    private static ConsumerQueueDepth poller(SimpleMeterRegistry registry, Channel channel) {
        Connection connection = mock(Connection.class);
        when(connection.isOpen()).thenReturn(true);
        try {
            when(connection.createChannel()).thenReturn(channel);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new ConsumerQueueDepth(registry, DOMAIN, Mono.just(connection),
                () -> Map.of(QUEUE, SubscriberStatus.healthy(QUEUE)), Duration.ofMillis(20));
    }

    private static double depth(SimpleMeterRegistry registry) {
        Gauge gauge = registry.find("reactive_commons.consumer.queue.depth")
                .tags("domain", DOMAIN, "queue", QUEUE).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }

    private static boolean eventually(BooleanSupplier condition) {
        try {
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(10)).until(condition::getAsBoolean);
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }
}
