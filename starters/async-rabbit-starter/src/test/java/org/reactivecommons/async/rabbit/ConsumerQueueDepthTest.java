package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.rabbit.listeners.SubscriberStatus;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
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
            assertThat(await(() -> depth(registry) == 7.0)).as("the gauge reads the broker's count").isTrue();
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
            assertThat(await(() -> {
                Gauge gauge = registry.find("reactive_commons.consumer.queue.depth").gauge();
                return gauge != null && Double.isNaN(gauge.value());
            })).as("an unknown depth reads NaN").isTrue();
        }
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

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
