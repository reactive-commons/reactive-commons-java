package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Runs the sender of the starter against a REAL RabbitMQ broker and checks that:
 * <ul>
 *     <li>the publishing flows are reported as live (the signal behind the health check);</li>
 *     <li>the threads of the sender executors are released when the application closes.</li>
 * </ul>
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class SenderLifecycleRabbitContainerTest {

    private static final Pattern SENDER_THREADS = Pattern.compile("^RMessageSender[12]-.*");

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Test
    void reportsLiveFlowsAndReleasesTheSenderThreadsWhenTheApplicationCloses() throws Exception {
        Set<String> before = senderThreads();
        Set<String> whileRunning;
        RabbitMQBrokerProvider provider;
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "slc",
                false, true, RabbitScenarioSupport.Handlers.class, SendEvents.class, ListenEvents.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "slc-app.subsEvents");
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            provider = (RabbitMQBrokerProvider) context.getBean(ConnectionManager.class)
                    .getProviders().get(RabbitScenarioSupport.DEFAULT_DOMAIN);

            Flux.range(0, 300)
                    .flatMap(i -> Mono.from(bus.emit(new DomainEvent<>("scenario.event",
                            UUID.randomUUID().toString(), Map.of("id", i)))), 50)
                    .blockLast(Duration.ofSeconds(60));

            // against a real broker with live connection and flows the health check reports UP
            assertThat(provider.healthCheck().block().status()).isEqualTo(RCHealth.Status.UP);
            assertThat(provider.sender().publisherStatus().confirmFlowsLive()).isTrue();
            assertThat(provider.sender().publisherStatus().consecutiveConfirmFailures()).isZero();
            whileRunning = senderThreads();
        }

        Set<String> created = new HashSet<>(whileRunning);
        created.removeAll(before);
        assertThat(created).as("sender threads created by the traffic").isNotEmpty();

        // the threads of the application must disappear once it is closed
        await().atMost(Duration.ofSeconds(10)).until(() -> senderThreads().stream().noneMatch(created::contains));
        Set<String> afterClose = senderThreads();
        afterClose.retainAll(created);

        System.out.printf("[REAL BROKER] sender threads created=%d, still alive 10 s after closing the "
                + "application=%d%n", created.size(), afterClose.size());
        assertThat(afterClose).as("sender threads left after closing the application").isEmpty();
    }

    private static Set<String> senderThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> SENDER_THREADS.matcher(name).matches())
                .collect(java.util.stream.Collectors.toSet());
    }
}
