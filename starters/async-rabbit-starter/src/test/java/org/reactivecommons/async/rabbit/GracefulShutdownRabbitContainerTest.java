package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;

/**
 * Closes the application against a REAL RabbitMQ broker while its handlers are running, and checks that:
 * <ul>
 *     <li>the running handlers finish and their deliveries are acknowledged, so the broker does not redeliver
 *     (and the application does not handle twice) messages that were already handled;</li>
 *     <li>the connections are closed with the AMQP handshake instead of having their socket cut.</li>
 * </ul>
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class GracefulShutdownRabbitContainerTest {

    private static final int COMMANDS = 5;
    private static final Duration HANDLER_DURATION = Duration.ofMillis(1_500);
    private static final String QUEUE = "gsd-app";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private static final AtomicInteger HANDLED = new AtomicInteger();
    private static volatile CountDownLatch started = new CountDownLatch(0);

    /**
     * A command handler that does not block its thread while it works, like a handler calling a reactive client.
     */
    @Configuration
    static class SlowHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .handleCommand("scenario.command", command -> {
                        started.countDown();
                        return Mono.delay(HANDLER_DURATION).doOnNext(ignored -> HANDLED.incrementAndGet()).then();
                    }, Map.class);
        }
    }

    @Test
    void closingTheApplicationLetsTheRunningHandlersFinishAndAcknowledgeTheirDeliveries() throws Exception {
        HANDLED.set(0);
        started = new CountDownLatch(COMMANDS);
        AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "gsd", false,
                false, SlowHandlers.class, SendDirect.class, ListenCommands.class);
        try {
            RabbitScenarioSupport.awaitConsumers(RABBIT, QUEUE);
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);
            Flux.range(0, COMMANDS)
                    .flatMap(i -> gateway.sendCommand(new Command<>("scenario.command", UUID.randomUUID().toString(),
                            Map.of("id", i)), QUEUE))
                    .blockLast(Duration.ofSeconds(30));
            assertThat(started.await(30, TimeUnit.SECONDS)).as("every handler started").isTrue();
        } finally {
            context.close();
        }

        await().alias("connections closed").atMost(Duration.ofSeconds(15))
                .until(() -> RabbitScenarioSupport.connectionsOf(RABBIT, "gsd-") == 0);
        long messagesLeft = messagesIn(QUEUE);
        boolean socketCut = RABBIT.getLogs().contains("client unexpectedly closed TCP connection");
        System.out.printf("[REAL BROKER] handlers finished=%d/%d, messages left in the queue=%d, connection socket "
                + "cut=%s%n", HANDLED.get(), COMMANDS, messagesLeft, socketCut);

        assertThat(HANDLED.get()).as("handlers that finished before the application closed").isEqualTo(COMMANDS);
        assertThat(messagesLeft).as("handled messages the broker would redeliver").isZero();
        assertThat(RabbitScenarioSupport.connectionsOf(RABBIT, "gsd-")).as("connections left open").isZero();
        assertThat(socketCut).as("a connection closed without the AMQP close handshake").isFalse();
    }

    /**
     * Messages of the queue, ready or unacknowledged.
     */
    private static long messagesIn(String queue) throws Exception {
        String listing = RABBIT.execInContainer("rabbitmqctl", "-q", "list_queues", "name", "messages").getStdout();
        return listing.lines()
                .map(line -> line.trim().split("\\s+"))
                .filter(columns -> columns.length == 2 && columns[0].equals(queue))
                .mapToLong(columns -> Long.parseLong(columns[1]))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("queue " + queue + " not found: " + listing));
    }
}
