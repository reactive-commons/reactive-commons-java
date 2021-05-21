package org.reactivecommons.test;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableMessageListeners;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.UnicastProcessor;
import reactor.test.StepVerifier;

import java.time.Duration;

import static reactor.core.publisher.Mono.from;
import static reactor.core.publisher.Mono.fromRunnable;

@SpringBootTest
public class DynamicRegistryTest {

    @Autowired
    private DomainEventBus eventBus;

    @Autowired
    private DynamicRegistry dynamicRegistry;

    @Value("${spring.application.name}")
    private String appName;

    @Test
    void shouldReceiveResponse() {
        UnicastProcessor<String> result = UnicastProcessor.create();
        EventHandler<String> fn = message -> fromRunnable(() -> result.onNext(message.getData()));

        dynamicRegistry.listenEvent("test.event", fn, String.class).block();
        final Publisher<Void> emit = eventBus.emit(new DomainEvent<>("test.event", "42", "Hello"));
        from(emit).block();

        StepVerifier.create(result.next().timeout(Duration.ofSeconds(10)))
                .expectNext("Hello")
                .verifyComplete();


    }


    @SpringBootApplication
    @EnableMessageListeners
    @EnableDomainEventBus
    static class App {
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }

    }
}
