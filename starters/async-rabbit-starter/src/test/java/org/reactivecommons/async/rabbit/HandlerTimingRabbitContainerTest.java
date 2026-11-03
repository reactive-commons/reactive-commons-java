package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;

/**
 * Handles a command from a REAL RabbitMQ broker while the system clock is stepped forward (as an NTP correction does),
 * and checks that the durations the listener measures (the {@code reactive_commons.consumer.handler.*} timers) do not
 * change: a duration measured on the system clock grows (or shrinks) by the step, so the latency of the handlers and
 * the alerts on it would follow the clock instead of the handlers.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class HandlerTimingRabbitContainerTest {

    private static final String PREFIX = "htm";
    private static final Duration CLOCK_STEP = Duration.ofHours(1);
    private static final String EXECUTION = "reactive_commons.consumer.handler.execution";
    private static final String LATENCY = "reactive_commons.consumer.handler.latency";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Configuration
    static class ClockSteppingHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .handleCommand("timing.command", command -> {
                        // only on the thread running the handler, until its delivery is handled and acknowledged
                        MockedStatic<Instant> clock = Mockito.mockStatic(Instant.class, Mockito.CALLS_REAL_METHODS);
                        Instant stepped = Instant.ofEpochMilli(System.currentTimeMillis() + CLOCK_STEP.toMillis());
                        clock.when(Instant::now).thenReturn(stepped);
                        return Mono.<Void>empty().doFinally(signal -> clock.close());
                    }, Map.class);
        }
    }

    @Test
    void aStepOfTheSystemClockDoesNotChangeTheDurationsMeasuredByTheListener() {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, PREFIX,
                false, false, ClockSteppingHandlers.class, SendDirect.class, ListenCommands.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, PREFIX + "-app");
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            context.getBean(DirectAsyncGateway.class)
                    .sendCommand(new Command<>("timing.command", UUID.randomUUID().toString(), Map.of()),
                            PREFIX + "-app")
                    .block(Duration.ofSeconds(30));
            await().atMost(Duration.ofSeconds(30)).until(() -> count(registry, LATENCY) == 1);

            double executionSeconds = totalSeconds(registry, EXECUTION);
            double latencySeconds = totalSeconds(registry, LATENCY);
            System.out.printf("[REAL BROKER] command handled while the clock was stepped %s forward: handler "
                    + "execution %.3f s, handler latency %.3f s%n", CLOCK_STEP, executionSeconds, latencySeconds);
            assertThat(executionSeconds).as("execution of the handler, in seconds").isLessThan(10);
            assertThat(latencySeconds).as("latency of the handler, in seconds").isLessThan(10);
        }
    }

    private static long count(MeterRegistry registry, String timer) {
        return registry.find(timer).timers().stream().mapToLong(Timer::count).sum();
    }

    private static double totalSeconds(MeterRegistry registry, String timer) {
        return registry.find(timer).timers().stream().mapToDouble(t -> t.totalTime(TimeUnit.SECONDS)).sum();
    }
}
