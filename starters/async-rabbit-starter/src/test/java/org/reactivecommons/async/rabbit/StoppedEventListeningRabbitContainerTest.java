package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.starter.impl.listener.rabbit.RabbitMQListenerOnlyConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;

/**
 * Stops listening to an event with the {@link DynamicRegistry} and makes the event listener restart against a REAL
 * RabbitMQ broker (the broker cancels its consumer because its queue was deleted, and the listener declares its
 * topology again): the event must stay unbound, otherwise its messages arrive again although nobody asked for them.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class StoppedEventListeningRabbitContainerTest {

    private static final String PREFIX = "sel";
    private static final String QUEUE = PREFIX + "-app.subsEvents";
    private static final String STOPPED = "sel.stopped";
    private static final String KEPT = "sel.kept";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Configuration
    static class Handlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent(STOPPED, event -> Mono.empty(), Map.class)
                    .listenEvent(KEPT, event -> Mono.empty(), Map.class);
        }
    }

    @Test
    void anEventWhoseListeningWasStoppedStaysUnboundWhenTheListenerRestarts() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, PREFIX,
                false, false, Handlers.class, ListenEvents.class, RabbitMQListenerOnlyConfig.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, QUEUE);
            await().atMost(Duration.ofSeconds(30)).until(() -> boundEvents().containsAll(List.of(STOPPED, KEPT)));

            context.getBean(DynamicRegistry.class).stopListeningEvent(STOPPED).block(Duration.ofSeconds(30));
            assertThat(boundEvents()).as("events bound once the listening was stopped").doesNotContain(STOPPED);

            // the broker cancels the consumer: the listener restarts and declares its queue and bindings again
            RABBIT.execInContainer("rabbitmqctl", "delete_queue", QUEUE);
            RabbitScenarioSupport.awaitConsumers(RABBIT, QUEUE);
            await().atMost(Duration.ofSeconds(30)).until(() -> boundEvents().contains(KEPT));
            List<String> bound = boundEvents();

            System.out.printf("[REAL BROKER] events bound once the listener restarted: %s%n", bound);
            assertThat(bound).as("events bound once the listener restarted").contains(KEPT).doesNotContain(STOPPED);
        }
    }

    /**
     * Routing keys of the bindings of the events exchange to the queue of the listener.
     */
    private static List<String> boundEvents() throws Exception {
        String listing = RABBIT.execInContainer("rabbitmqctl", "-q", "list_bindings", "source_name",
                "destination_name", "routing_key").getStdout();
        return listing.lines().map(line -> line.split("\t"))
                .filter(columns -> columns.length == 3 && columns[0].equals("domainEvents")
                        && columns[1].equals(QUEUE))
                .map(columns -> columns[2])
                .toList();
    }
}
