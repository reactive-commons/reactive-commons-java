package org.reactivecommons.test;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableMessageListeners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.UnicastProcessor;
import reactor.test.StepVerifier;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.core.publisher.Mono.*;

@SpringBootTest
@RunWith(SpringRunner.class)
public class SimpleEventNotificationTest {

    private static final String EVENT_NAME = "simpleTestEvent";

    @Autowired
    private DomainEventBus eventBus;

    @Autowired
    private UnicastProcessor<DomainEvent<Long>> listener;

    private String eventId = ThreadLocalRandom.current().nextInt() + "";
    private Long data = ThreadLocalRandom.current().nextLong();

    @Test
    public void shouldReceiveEvent() throws InterruptedException {
        DomainEvent<?> event = new DomainEvent<>(EVENT_NAME, eventId, data);
        from(eventBus.emit(event)).subscribe();
        StepVerifier.create(listener.take(1)).assertNext(evt ->
            assertThat(evt).extracting(DomainEvent::getName, DomainEvent::getEventId, DomainEvent::getData)
                .containsExactly(EVENT_NAME, eventId, data)
        ).verifyComplete();
    }



    @SpringBootApplication
    @EnableDomainEventBus
    @EnableMessageListeners
    static class App{
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }

        @Bean
        public HandlerRegistry registry(UnicastProcessor<DomainEvent<Long>>  listener) {
            return HandlerRegistry.register()
                .serveQuery("double", rqt -> just(rqt*2), Long.class)
                .listenEvent(EVENT_NAME, handle(listener), Long.class);
        }

        @Bean
        public UnicastProcessor<DomainEvent<Long>> listener() {
            return UnicastProcessor.create();
        }

        private EventHandler<Long> handle(UnicastProcessor<DomainEvent<Long>> listener) {
            return command -> {
                listener.onNext(command);
                return empty();
            };
        }
    }
}
