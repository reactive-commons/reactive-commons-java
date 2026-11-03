package org.reactivecommons.async.rabbit;

import org.awaitility.core.ConditionTimeoutException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;

/**
 * Closes, from a REAL RabbitMQ broker, the connections of an application whose {@link ConnectionFactoryCustomizer}
 * disables the automatic recovery of the client: nothing re-opens such a connection, so the application must open new
 * ones instead of keeping on with the closed ones until it is restarted.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConnectionWithoutRecoveryRabbitContainerTest {

    private static final String PREFIX = "cwr";
    private static final String QUEUE = PREFIX + "-app";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private static final AtomicInteger HANDLED = new AtomicInteger();

    /**
     * Registered before the configuration of the starter, so its customizer replaces the default one.
     */
    @Configuration
    static class CountingHandlersWithoutAutomaticRecovery {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .handleCommand("scenario.command", command -> Mono.fromRunnable(HANDLED::incrementAndGet),
                            Map.class);
        }

        @Bean
        ConnectionFactoryCustomizer connectionFactoryCustomizer() {
            return (factory, props) -> {
                factory.setAutomaticRecoveryEnabled(false);
                return factory;
            };
        }
    }

    @Test
    void anApplicationWhoseConnectionsAreClosedForGoodOpensNewOnes() throws Exception {
        HANDLED.set(0);
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, PREFIX,
                false, false, CountingHandlersWithoutAutomaticRecovery.class, SendDirect.class, ListenCommands.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, QUEUE);
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);
            send(gateway).block(Duration.ofSeconds(30));
            await().atMost(Duration.ofSeconds(30)).until(() -> HANDLED.get() == 1);

            // e.g. the broker node restarts, or an operator closes them
            RABBIT.execInContainer("rabbitmqctl", "close_all_connections", "test: closed for good");

            boolean handledAgain = eventually(() -> {
                send(gateway).timeout(Duration.ofSeconds(5)).onErrorResume(error -> Mono.empty()).block();
                return HANDLED.get() > 1;
            });
            System.out.printf("[REAL BROKER] connections closed without automatic recovery: handled again=%s, "
                    + "connections of the application=%d%n", handledAgain, RabbitScenarioSupport.connectionsOf(RABBIT,
                    PREFIX + "-"));
            assertThat(handledAgain).as("a command sent after the connections were closed is handled").isTrue();
        }
    }

    private static Mono<Void> send(DirectAsyncGateway gateway) {
        return gateway.sendCommand(new Command<>("scenario.command", UUID.randomUUID().toString(), Map.of()), QUEUE);
    }

    /**
     * Whether the condition holds within 40 seconds.
     */
    private static boolean eventually(Callable<Boolean> condition) {
        try {
            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500)).until(condition);
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }
}
