package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;
import static reactor.rabbitmq.ExchangeSpecification.exchange;

/**
 * The broker closes the connections of an application (e.g. a node restart) against a REAL RabbitMQ broker: the
 * exclusive reply queue, which the broker deletes with its connection, must be declared again by the automatic
 * topology recovery of the client, which records the declarations made with {@code asyncCompletableRpc}.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class TopologyRecoveryRabbitContainerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private static final MeterRegistry REGISTRY = new SimpleMeterRegistry();

    @Configuration
    static class QueryHandler {
        @Bean(destroyMethod = "")
        MeterRegistry meterRegistry() {
            return REGISTRY;
        }

        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register().serveQuery("recovery.query", query -> Mono.just("ok"), String.class);
        }
    }

    @Test
    void theReplyQueueIsRecoveredWithTheConnection() throws Exception {
        connectionLoss("trc", false);
    }

    /**
     * A channel error first closes the channel the reply queue was declared on, so the next declarations of the
     * application use a new channel.
     */
    @Test
    void theReplyQueueIsRecoveredWithTheConnectionAfterAChannelErrorOnItsDeclarationChannel() throws Exception {
        connectionLoss("tre", true);
    }

    private void connectionLoss(String prefix, boolean channelErrorFirst) throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, prefix,
                false, true, QueryHandler.class, ListenQueries.class, SendDirect.class)) {
            RabbitMQBrokerProvider provider = (RabbitMQBrokerProvider) context.getBean(ConnectionManager.class)
                    .getProviders().get(RabbitScenarioSupport.DEFAULT_DOMAIN);
            String appName = provider.props().getAppName();
            String replyQueue = provider.props().getBrokerConfigProps().getReplyQueue();
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);
            RabbitScenarioSupport.awaitConsumers(RABBIT, replyQueue);
            assertThat(query(gateway, appName)).as("answered before the connection loss").isEqualTo("ok");

            if (channelErrorFirst) {
                // the topology of the listeners' connection, where the reply queue was declared
                TopologyCreator topology = provider.receiver().topologyCreator();
                String name = "tre.exchange." + UUID.randomUUID();
                topology.declare(exchange(name).type("topic").durable(false).autoDelete(true)).block(TIMEOUT);
                assertThatThrownBy(() -> topology.declare(exchange(name).type("direct").durable(false)
                        .autoDelete(true)).block(TIMEOUT))
                        .hasStackTraceContaining("PRECONDITION_FAILED");
                topology.declare(exchange(name + ".other").type("direct").durable(false).autoDelete(true))
                        .block(TIMEOUT);
            }

            RABBIT.execInContainer("rabbitmqctl", "close_all_connections", "topology recovery test");
            long start = System.currentTimeMillis();
            awaitDeleted(replyQueue); // exclusive: the broker deletes it with its connection
            RabbitScenarioSupport.awaitConsumers(RABBIT, replyQueue);
            long recoveredAfter = System.currentTimeMillis() - start;
            String answer = query(gateway, appName);

            double restarts = REGISTRY.find("reactive_commons.consumer.restart").tag("queue", replyQueue)
                    .counters().stream().mapToDouble(Counter::count).sum();
            System.out.printf("[REAL BROKER] connection loss%s: reply queue consumed again after %d ms, "
                            + "query=%s, reply listener restarts=%s%n",
                    channelErrorFirst ? " after a channel error" : "", recoveredAfter, answer, restarts);
            assertThat(answer).as("answered after the connection loss").isEqualTo("ok");
            assertThat(restarts).as("restarts of the reply listener: the client recovered the queue").isZero();
        }
    }

    private static void awaitDeleted(String queue) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String listing = RABBIT.execInContainer("rabbitmqctl", "-q", "list_queues", "name").getStdout();
            if (listing.lines().map(String::trim).noneMatch(queue::equals)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("The queue " + queue + " was not deleted with its connection");
    }

    private static String query(DirectAsyncGateway gateway, String appName) {
        return gateway.requestReply(new AsyncQuery<>("recovery.query", "q"), appName, String.class)
                .onErrorResume(error -> Mono.just("failed: " + error))
                .block(TIMEOUT);
    }
}
