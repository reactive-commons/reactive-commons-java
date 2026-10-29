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
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.EXTRA_DOMAIN;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;

/**
 * Closes, against a REAL RabbitMQ broker, an application with two domains whose handlers do not finish in time (e.g.
 * waiting for a call that never answers): each domain waits up to {@link RabbitMQBrokerProvider#DRAIN_TIMEOUT} for its
 * deliveries in flight, and the domains must wait at the same time. Waiting one after another adds up the wait of
 * every domain, and can exceed the time the platform gives the application to stop (30 s by default in Kubernetes)
 * before killing it in the middle of the shutdown.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class MultiDomainShutdownRabbitContainerTest {

    private static final String PREFIX = "mds";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private static volatile CountDownLatch started = new CountDownLatch(0);

    @Configuration
    static class NeverEndingHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .handleCommand("scenario.command", command -> neverEnding(), Map.class)
                    .handleCommand(EXTRA_DOMAIN, "scenario.command", command -> neverEnding(), Map.class);
        }

        private static Mono<Void> neverEnding() {
            started.countDown();
            return Mono.never();
        }
    }

    @Test
    void closingTheApplicationWaitsForTheDeliveriesOfEveryDomainAtTheSameTime() throws Exception {
        started = new CountDownLatch(2);
        AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, PREFIX, true,
                false, NeverEndingHandlers.class, SendDirect.class, ListenCommands.class);
        long closeMillis;
        try {
            RabbitScenarioSupport.awaitConsumers(RABBIT, PREFIX + "-app", PREFIX + "-extra");
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);
            for (String queue : new String[]{PREFIX + "-app", PREFIX + "-extra"}) {
                gateway.sendCommand(new Command<>("scenario.command", UUID.randomUUID().toString(), Map.of()), queue)
                        .block(Duration.ofSeconds(30));
            }
            assertThat(started.await(30, TimeUnit.SECONDS)).as("a handler running on each domain").isTrue();
        } finally {
            long start = System.nanoTime();
            context.close();
            closeMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        }

        long drainMillis = RabbitMQBrokerProvider.DRAIN_TIMEOUT.toMillis();
        System.out.printf("[REAL BROKER] 2 domains with a delivery in flight that never finishes (drain timeout %d "
                + "ms): application closed in %d ms%n", drainMillis, closeMillis);
        assertThat(closeMillis).as("time to close the application").isLessThan(drainMillis * 3 / 2);
    }
}
