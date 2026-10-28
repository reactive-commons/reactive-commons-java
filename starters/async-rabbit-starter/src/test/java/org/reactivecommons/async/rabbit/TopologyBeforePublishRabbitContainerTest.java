package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Starts the starter against a REAL RabbitMQ broker where its exchanges do not exist yet, and checks that:
 * <ul>
 *     <li>an event published right after the application starts is published, i.e. the exchange the starter
 *     declares exists before the first publish reaches the broker;</li>
 *     <li>the reply queue of the direct gateway has a single consumer, however many times the gateway is requested.</li>
 * </ul>
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class TopologyBeforePublishRabbitContainerTest {

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    /**
     * Repeated: the first publish only races the declaration once the JVM is warm (8 of 10 runs failed before the
     * fix).
     */
    @RepeatedTest(5)
    void anEventPublishedRightAfterTheStartIsPublished() throws Exception {
        // a fresh exchange name for this test, so it does not exist on the broker yet
        String exchange = "tbp.events." + UUID.randomUUID();
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "tbp",
                false, false, Map.of("app.async.app.domain.events.exchange", exchange),
                RabbitScenarioSupport.Handlers.class, SendEvents.class)) {
            DomainEventBus bus = context.getBean(DomainEventBus.class);

            Throwable error = Mono.from(bus.emit(new DomainEvent<>("scenario.event", UUID.randomUUID().toString(),
                            Map.of("id", 1))))
                    .timeout(Duration.ofSeconds(30))
                    .flatMap(ignored -> Mono.<Throwable>empty())
                    .onErrorResume(Mono::just)
                    .block();

            System.out.printf("[REAL BROKER] first event right after the start: %s%n",
                    error == null ? "published" : error);
            assertThat(error).as("the first event published after the start").isNull();
        }
    }

    @Test
    void theReplyQueueHasASingleConsumerWhenTheGatewayIsRequestedAgain() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "tbr",
                false, true, RabbitScenarioSupport.Handlers.class, SendDirect.class)) {
            RabbitMQBrokerProvider provider = (RabbitMQBrokerProvider) context.getBean(ConnectionManager.class)
                    .getProviders().get(RabbitScenarioSupport.DEFAULT_DOMAIN);
            String replyQueue = provider.props().getBrokerConfigProps().getReplyQueue();
            RabbitScenarioSupport.awaitConsumers(RABBIT, replyQueue);

            provider.getDirectAsyncGateway();

            // a second reply listener would register its consumer asynchronously
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                    .until(() -> consumersOf(replyQueue) <= 1);
            int consumers = consumersOf(replyQueue);
            System.out.printf("[REAL BROKER] consumers of the reply queue after requesting the gateway twice: %d%n",
                    consumers);
            assertThat(consumers).as("consumers of the reply queue").isEqualTo(1);
        }
    }

    private static int consumersOf(String queue) throws Exception {
        String listing = RABBIT.execInContainer("rabbitmqctl", "-q", "list_queues", "name", "consumers").getStdout();
        return listing.lines()
                .map(line -> line.trim().split("\\s+"))
                .filter(columns -> columns.length == 2 && columns[0].equals(queue))
                .mapToInt(columns -> Integer.parseInt(columns[1]))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("queue " + queue + " not found: " + listing));
    }
}
