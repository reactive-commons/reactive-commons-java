package org.reactivecommons.async.rabbit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;

/**
 * Delivers to a listener of the starter, on a REAL RabbitMQ broker, events whose {@code x-death} header is not shaped
 * as the broker writes it (e.g. copied by another client that encodes its {@code count} as a 32-bit integer, or set to
 * something else entirely). Reading the header must never terminate the listener: that redelivers every delivery in
 * flight on its channel, and loops while the handler of such a message fails.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class MalformedDeathHeaderRabbitContainerTest {

    private static final String QUEUE = "xdh-app.subsEvents";
    private static final String FAILING = "failing";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private static final Set<String> HANDLED = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger FAILURES = new AtomicInteger();

    private final AtomicInteger restarts = new AtomicInteger();
    private final AppenderBase<ILoggingEvent> restartCounter = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            if (event.getFormattedMessage().startsWith("Re-subscribing listener for queue " + QUEUE)) {
                restarts.incrementAndGet();
            }
        }
    };

    @Configuration
    static class RecordingHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("scenario.event", event -> {
                        if (FAILING.equals(event.getEventId())) {
                            FAILURES.incrementAndGet();
                            return Mono.error(new IllegalStateException("the handler fails"));
                        }
                        HANDLED.add(event.getEventId());
                        return Mono.empty();
                    }, Map.class);
        }
    }

    @BeforeEach
    void countRestarts() {
        restartCounter.start();
        rootLogger().addAppender(restartCounter);
    }

    @AfterEach
    void stopCounting() {
        rootLogger().detachAppender(restartCounter);
    }

    @Test
    void aMalformedDeathHeaderNeverTerminatesTheListener() throws Exception {
        HANDLED.clear();
        FAILURES.set(0);
        try (AnnotationConfigApplicationContext ignored = RabbitScenarioSupport.startWithHandlers(RABBIT, "xdh",
                false, false, Map.of("app.async.app.retryDelay", "200"), RecordingHandlers.class, ListenEvents.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, QUEUE);

            // the Java client encodes an Integer as a 32-bit AMQP integer, as other clients may do
            List<Map<String, Object>> intCount = List.of(Map.of("count", 1, "queue", QUEUE, "reason", "rejected"));
            try (Connection connection = adminFactory().newConnection("xdh-admin");
                 Channel channel = connection.createChannel()) {
                publish(channel, "int-count", intCount);
                publish(channel, "not-a-list", "rejected");
                publish(channel, "not-a-table", List.of("rejected"));
                publish(channel, "well-formed", List.of(Map.of("count", 1L, "queue", QUEUE, "reason", "rejected")));
                publish(channel, "no-death-header", null);
                publish(channel, FAILING, intCount);
            }

            await().atMost(Duration.ofSeconds(15)).until(() -> HANDLED.size() >= 5 && FAILURES.get() >= 5);
            System.out.printf("[REAL BROKER] events handled: %s, attempts of the failing one=%d, listener "
                    + "restarts=%d%n", HANDLED, FAILURES.get(), restarts.get());

            assertThat(HANDLED).as("events handled by the listener")
                    .containsExactlyInAnyOrder("int-count", "not-a-list", "not-a-table", "well-formed",
                            "no-death-header");
            assertThat(FAILURES.get()).as("attempts of the event whose handler fails (fast retries)")
                    .isGreaterThanOrEqualTo(5);
            assertThat(restarts.get()).as("listener restarts caused by the x-death headers").isZero();
        }
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }

    private static void publish(Channel channel, String eventId, Object deathHeader) throws Exception {
        String body = "{\"name\":\"scenario.event\",\"eventId\":\"" + eventId + "\",\"data\":{}}";
        AMQP.BasicProperties.Builder properties = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .messageId(eventId);
        if (deathHeader != null) {
            properties.headers(Map.of("x-death", deathHeader));
        }
        // as a publisher of the event does: the listener resolves the handler from the routing key
        channel.basicPublish("domainEvents", "scenario.event", properties.build(),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static ConnectionFactory adminFactory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        return factory;
    }
}
