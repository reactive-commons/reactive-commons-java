package org.reactivecommons.test;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.DomainEventHandler;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableMessageListeners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.from;
import static reactor.core.publisher.Mono.just;

@SpringBootTest
class SimpleEventNotificationTest {

    private static final String EVENT_NAME = "simpleTestEvent";

    @Autowired
    private DomainEventBus eventBus;

    private final String eventId = ThreadLocalRandom.current().nextInt() + "";
    private final Long data = ThreadLocalRandom.current().nextLong();

    @Test
    void shouldReceiveEvent() {
        DomainEvent<?> event = new DomainEvent<>(EVENT_NAME, eventId, data);
        Sinks.Many<DomainEvent<Long>> listener = Sinks.many().unicast().onBackpressureBuffer();
        from(eventBus.emit(event)).subscribe();
        StepVerifier.create(listener.asFlux().take(1)).assertNext(evt ->
                assertThat(evt).extracting(DomainEvent::getName, DomainEvent::getEventId, DomainEvent::getData)
                        .containsExactly(EVENT_NAME, eventId, data)
        ).verifyComplete();
    }


    @SpringBootApplication
    @EnableDomainEventBus
    @EnableMessageListeners
    static class App {
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }

        @Bean
        public HandlerRegistry registry(Sinks.Many<DomainEvent<Long>> listener) {
            return HandlerRegistry.register()
                    .serveQuery("double", rqt -> just(rqt * 2), Long.class)
                    .listenEvent(EVENT_NAME, handle(listener), Long.class);
        }

        @Bean
        public Sinks.Many<DomainEvent<Long>> listener() {
            return Sinks.many().unicast().onBackpressureBuffer();
        }

        private DomainEventHandler<Long> handle(Sinks.Many<DomainEvent<Long>> listener) {
            return command -> {
                listener.emitNext(command, Sinks.EmitFailureHandler.FAIL_FAST);
                return empty();
            };
        }
    }
}
