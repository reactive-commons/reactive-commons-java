package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Closes an application context while another one keeps running in the same JVM (e.g. tests, DevTools) against a
 * REAL RabbitMQ broker: the connections and the threads of the closed one are released, those shared with the running
 * one (the Netty event loop group, the global schedulers) are kept.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ClosedContextResourcesRabbitContainerTest {

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Test
    void theResourcesOfAClosedContextAreReleased() throws Exception {
        try (AnnotationConfigApplicationContext running = start("ccr")) {
            Mono.from(running.getBean(DomainEventBus.class).emit(new DomainEvent<>("scenario.event", "1", Map.of())))
                    .block();
            Set<Thread> before = Thread.getAllStackTraces().keySet();

            try (AnnotationConfigApplicationContext closed = start("ccc")) {
                Mono.from(closed.getBean(DomainEventBus.class).emit(new DomainEvent<>("scenario.event", "2",
                        Map.of()))).block();
                RabbitScenarioSupport.awaitConsumers(RABBIT, "ccc-app");
            }
            Thread.sleep(3_000);

            Map<String, Long> leftThreads = Thread.getAllStackTraces().keySet().stream()
                    .filter(thread -> !before.contains(thread) && thread.isAlive())
                    .collect(Collectors.groupingBy(thread -> thread.getName().replaceAll("\\d+", "#")
                                    + (thread.isDaemon() ? " (daemon)" : ""), TreeMap::new, Collectors.counting()));
            int connections = RabbitScenarioSupport.connectionsOf(RABBIT, "ccc-");
            System.out.printf("[REAL BROKER] closed context: connections=%d, channels=%s, threads left=%s%n",
                    connections, RabbitScenarioSupport.channelsOf(RABBIT, "ccc-"), leftThreads);
            assertThat(connections).as("connections of the closed context").isZero();
            assertThat(leftThreads).as("threads of the channel pool of the closed context")
                    .doesNotContainKey("sender-channel-pool-#");
        }
    }

    /**
     * Two contexts with the same properties share their connections: closing one keeps them for the other.
     */
    @Test
    void theConnectionsSharedWithARunningContextAreKept() throws Exception {
        try (AnnotationConfigApplicationContext running = start("ccs")) {
            try (AnnotationConfigApplicationContext closed = start("ccs")) {
                Mono.from(closed.getBean(DomainEventBus.class).emit(new DomainEvent<>("scenario.event", "1",
                        Map.of()))).block();
            }

            int connections = RabbitScenarioSupport.connectionsOf(RABBIT, "ccs-");
            Throwable error = Mono.from(running.getBean(DomainEventBus.class).emit(new DomainEvent<>("scenario.event",
                            "2", Map.of())))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .then(Mono.<Throwable>empty())
                    .onErrorResume(Mono::just)
                    .block();
            System.out.printf("[REAL BROKER] after closing a context sharing the connections: connections=%d, "
                    + "publish of the running one=%s%n", connections, error == null ? "ok" : error);
            assertThat(connections).as("connections shared with the running context").isEqualTo(2);
            assertThat(error).as("publish of the running context").isNull();
        }
    }

    private static AnnotationConfigApplicationContext start(String prefix) {
        return RabbitScenarioSupport.startWithHandlers(RABBIT, prefix, false, true,
                RabbitScenarioSupport.Handlers.class, SendEvents.class, SendDirect.class, ListenEvents.class,
                ListenCommands.class, ListenQueries.class);
    }
}
