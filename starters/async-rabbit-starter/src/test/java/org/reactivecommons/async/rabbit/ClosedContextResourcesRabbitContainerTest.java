package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.rabbit.RabbitScenarioSupport.Scenario;
import org.mockito.invocation.InvocationOnMock;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
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
    private static final Duration BLOCKING = Duration.ofSeconds(30);

    /**
     * Holds back the connections of the contexts started with {@link GatedConnections}.
     */
    private static volatile ConnectionGate gate = new ConnectionGate();

    /**
     * A context closed while its connections are still being opened (e.g. right after it started) leaves none of
     * them open on the broker. The connections of the closed context are held back until it is closed, so the
     * test does not depend on how fast the broker accepts them.
     */
    @Test
    void theConnectionsOpenedWhileAContextClosesAreClosed() throws Exception {
        try (AnnotationConfigApplicationContext running = start("cco")) {
            // the running context keeps the shared event loop group, which the held back connections use
            Mono.from(running.getBean(DomainEventBus.class).emit(new DomainEvent<>("scenario.event", "1", Map.of())))
                    .block();
            ConnectionGate closedGate = new ConnectionGate();
            gate = closedGate;
            AnnotationConfigApplicationContext closed = RabbitScenarioSupport.startWithHandlers(RABBIT,
                    new Scenario("ccg", false, true), GatedConnections.class, SendEvents.class, ListenEvents.class,
                    ListenCommands.class, ListenQueries.class);
            try {
                assertThat(closedGate.opening.await(BLOCKING.toMillis(), TimeUnit.MILLISECONDS))
                        .as("a connection of the context is being opened").isTrue();
            } finally {
                closed.close();
                closedGate.open.countDown();
            }

            await().alias("the held back connections opened").atMost(BLOCKING)
                    .until(() -> closedGate.inFlight.get() == 0);
            await().alias("no connection of the closed context left on the broker").atMost(Duration.ofSeconds(15))
                    .during(Duration.ofSeconds(1)).pollInterval(Duration.ofMillis(200))
                    .until(() -> RabbitScenarioSupport.connectionsOf(RABBIT, "ccg-") == 0);
            System.out.printf("[REAL BROKER] context closed while opening %d connection(s): connections left=%d%n",
                    closedGate.attempts.get(), RabbitScenarioSupport.connectionsOf(RABBIT, "ccg-"));
        }
    }

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
            await().atMost(Duration.ofSeconds(15)).until(() -> RabbitScenarioSupport.connectionsOf(RABBIT, "ccc-") == 0
                    && !threadsStartedAfter(before).containsKey("sender-channel-pool-#"));

            Map<String, Long> leftThreads = threadsStartedAfter(before);
            int connections = RabbitScenarioSupport.connectionsOf(RABBIT, "ccc-");
            System.out.printf("[REAL BROKER] closed context: connections=%d, channels=%s, threads left=%s%n",
                    connections, RabbitScenarioSupport.channelsOf(RABBIT, "ccc-"), leftThreads);
            assertThat(connections).as("connections of the closed context").isZero();
            assertThat(leftThreads).as("threads of the channel pool of the closed context")
                    .doesNotContainKey("sender-channel-pool-#");
        }
    }

    /**
     * Two contexts with the same properties, and so the same connection names: closing one keeps the connections of
     * the other. The contexts do not share their connections: the connections are cached per {@code AsyncProps}
     * instance, and each context binds its own; the test would hold as well if they shared them.
     * <p>
     * The closed context is closed once its connections are established (closing it while they are being opened is
     * {@link #theConnectionsOpenedWhileAContextClosesAreClosed()}). The broker forgets a closed connection shortly after
     * its close handshake, not when the client returns from it: the count is awaited.
     */
    @Test
    void theConnectionsSharedWithARunningContextAreKept() throws Exception {
        try (AnnotationConfigApplicationContext running = start("ccs")) {
            Mono.from(running.getBean(DomainEventBus.class).emit(new DomainEvent<>("scenario.event", "0", Map.of())))
                    .block();
            List<Connection> runningConnections = awaitEstablishedConnections(running);
            try (AnnotationConfigApplicationContext closed = start("ccs")) {
                Mono.from(closed.getBean(DomainEventBus.class).emit(new DomainEvent<>("scenario.event", "1",
                        Map.of()))).block();
                awaitEstablishedConnections(closed);
            }
            await().alias("only the connections of the running context are left").atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> RabbitScenarioSupport.connectionsOf(RABBIT, "ccs-") == runningConnections.size());

            int connections = RabbitScenarioSupport.connectionsOf(RABBIT, "ccs-");
            assertThat(establishedConnections(running)).as("connections of the running context, the same ones")
                    .containsExactlyElementsOf(runningConnections)
                    .allMatch(Connection::isOpen);
            Throwable error = Mono.from(running.getBean(DomainEventBus.class).emit(new DomainEvent<>("scenario.event",
                            "2", Map.of())))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .then(Mono.<Throwable>empty())
                    .onErrorResume(Mono::just)
                    .block();
            System.out.printf("[REAL BROKER] after closing a context with the same properties: connections=%d, "
                    + "publish of the running one=%s%n", connections, error == null ? "ok" : error);
            assertThat(connections).as("connections left: those of the running context").isEqualTo(2);
            assertThat(error).as("publish of the running context").isNull();
        }
    }

    private static AnnotationConfigApplicationContext start(String prefix) {
        return RabbitScenarioSupport.startWithHandlers(RABBIT, new Scenario(prefix, false, true),
                RabbitScenarioSupport.Handlers.class, SendEvents.class, SendDirect.class, ListenEvents.class,
                ListenCommands.class, ListenQueries.class);
    }

    /**
     * The sender and listener connections of the default domain of the context, once both are established.
     */
    private static List<Connection> awaitEstablishedConnections(AnnotationConfigApplicationContext context) {
        return await().alias("the sender and listener connections established").atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> establishedConnections(context), connections -> connections.size() == 2);
    }

    private static List<Connection> establishedConnections(AnnotationConfigApplicationContext context) {
        // the factory cached for the AsyncProps of the domain, the one its broker provider uses
        ConnectionFactory factory = RabbitMQSetupUtils.connectionFactoryProvider(
                context.getBean(AsyncPropsDomain.class).getProps(RabbitScenarioSupport.DEFAULT_DOMAIN),
                context.getBean(ConnectionFactoryCustomizer.class)).getConnectionFactory();
        return Stream.of(RabbitMQSetupUtils.establishedConnection(factory),
                        RabbitMQSetupUtils.establishedListenerConnection(factory))
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * The handlers of the scenario, with connection factories whose connections wait for the {@link #gate}.
     */
    @Configuration
    @Import(RabbitScenarioSupport.Handlers.class)
    static class GatedConnections {
        @Bean
        ConnectionFactoryCustomizer gatedConnectionFactory() {
            return (factory, props) -> gate.hold(factory);
        }
    }

    /**
     * Lets the connections of a factory open once {@link #open} is counted down.
     */
    static final class ConnectionGate {
        private final CountDownLatch opening = new CountDownLatch(1);
        private final CountDownLatch open = new CountDownLatch(1);
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger attempts = new AtomicInteger();

        ConnectionFactory hold(ConnectionFactory factory) {
            ConnectionFactory held = spy(factory);
            try {
                doAnswer(this::openWhenAllowed).when(held).newConnection(anyString());
            } catch (IOException | TimeoutException e) {
                throw new IllegalStateException(e);
            }
            return held;
        }

        private Object openWhenAllowed(InvocationOnMock invocation) throws Throwable {
            inFlight.incrementAndGet();
            attempts.incrementAndGet();
            try {
                opening.countDown();
                assertThat(open.await(BLOCKING.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
                return invocation.callRealMethod();
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    /**
     * The live threads started after {@code before}, counted by name (without its numbers).
     */
    private static Map<String, Long> threadsStartedAfter(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !before.contains(thread) && thread.isAlive())
                .collect(Collectors.groupingBy(thread -> thread.getName().replaceAll("\\d+", "#")
                        + (thread.isDaemon() ? " (daemon)" : ""), TreeMap::new, Collectors.counting()));
    }
}
