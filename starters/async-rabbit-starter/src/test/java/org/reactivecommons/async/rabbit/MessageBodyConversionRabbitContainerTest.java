package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.commons.converters.json.ObjectMapperSupplier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Consumes an event, a command and a query from a REAL RabbitMQ broker, and checks that their data is converted
 * straight from the body of the message into the class of its handler: building a JSON tree of the data first (and
 * converting the tree afterward) parses every message twice, which costs CPU and garbage in proportion to its size.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class MessageBodyConversionRabbitContainerTest {

    private static final String PREFIX = "mbc";
    private static final String APP = PREFIX + "-app";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private static final CountingNodeFactory NODES = new CountingNodeFactory();
    private static final List<Payload> RECEIVED = new CopyOnWriteArrayList<>();

    public record Payload(String id, int amount, List<String> tags) {
    }

    /**
     * Counts the JSON objects built as trees by the mapper of the application.
     */
    static final class CountingNodeFactory extends JsonNodeFactory {
        private final AtomicInteger objects = new AtomicInteger();

        @Override
        public ObjectNode objectNode() {
            objects.incrementAndGet();
            return super.objectNode();
        }
    }

    @Configuration
    static class CountingMapper {
        @Bean
        ObjectMapperSupplier objectMapperSupplier() {
            return () -> new DefaultObjectMapperSupplier().get().rebuild().nodeFactory(NODES).build();
        }
    }

    @Configuration
    static class Handlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("conversion.event", event -> received(event.getData()), Payload.class)
                    .handleCommand("conversion.command", command -> received(command.getData()), Payload.class)
                    .serveQuery("conversion.query", query -> received(query).thenReturn("ok"), Payload.class);
        }

        private static Mono<Void> received(Payload payload) {
            return Mono.fromRunnable(() -> RECEIVED.add(payload));
        }
    }

    @Test
    void theDataOfTheMessagesIsConvertedStraightFromTheirBody() {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, PREFIX,
                false, true, Map.of(), Handlers.class, CountingMapper.class, SendEvents.class, SendDirect.class,
                ListenEvents.class, ListenCommands.class, ListenQueries.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, APP + ".subsEvents", APP, APP + ".query");
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);
            Payload payload = new Payload("p-1", 42, List.of("a", "b"));
            RECEIVED.clear();
            NODES.objects.set(0);

            Mono.from(bus.emit(new DomainEvent<>("conversion.event", UUID.randomUUID().toString(), payload)))
                    .block(Duration.ofSeconds(30));
            gateway.sendCommand(new Command<>("conversion.command", UUID.randomUUID().toString(), payload), APP)
                    .block(Duration.ofSeconds(30));
            String reply = gateway.requestReply(new AsyncQuery<>("conversion.query", payload), APP, String.class)
                    .block(Duration.ofSeconds(30));
            await().atMost(Duration.ofSeconds(30)).until(() -> RECEIVED.size() == 3);

            System.out.printf("[REAL BROKER] event, command and query handled with %s: %d JSON objects built as "
                    + "trees to convert them%n", RECEIVED, NODES.objects.get());
            assertThat(RECEIVED).as("data received by the handlers").hasSize(3).containsOnly(payload);
            assertThat(reply).as("reply to the query").isEqualTo("ok");
            assertThat(NODES.objects.get()).as("JSON objects built as trees to convert the messages").isZero();
        }
    }
}
