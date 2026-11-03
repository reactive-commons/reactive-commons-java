package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.exceptions.SendFailureReturnedException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * Emits an event that the REAL RabbitMQ broker cannot deliver, and checks that its emitter gets the typed exception
 * of the outcome (see "Publisher outcome exceptions" in the documentation): it tells a message that was not published,
 * which is safe to emit again, from one that may have been, which can be duplicated by emitting it again.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class DomainEventBusFailureRabbitContainerTest {

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Test
    void anEventReturnedByTheBrokerFailsWithTheTypedExceptionOfItsOutcome() {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "ebf",
                false, false, Map.of("app.async.app.mandatory", "true"), RabbitScenarioSupport.Handlers.class,
                SendEvents.class)) {
            DomainEventBus bus = context.getBean(DomainEventBus.class);

            // mandatory, and no queue is bound to its name: the broker returns it
            Throwable error = catchThrowable(() -> Mono.from(bus.emit(new DomainEvent<>("nobody.listens",
                    UUID.randomUUID().toString(), Map.of()))).block(Duration.ofSeconds(30)));

            System.out.printf("[REAL BROKER] emitter of a returned event got %s (cause: %s)%n", error,
                    error == null ? null : error.getCause());
            assertThat(error).as("error the emitter of a returned event gets")
                    .isInstanceOf(SendFailureReturnedException.class);
        }
    }
}
