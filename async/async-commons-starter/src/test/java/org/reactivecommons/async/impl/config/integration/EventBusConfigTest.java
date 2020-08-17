package org.reactivecommons.async.impl.config.integration;

import static org.mockito.Mockito.*;
import static reactor.core.publisher.Mono.from;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.config.EventBusConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.Mono;

@SpringBootTest
@RunWith(SpringRunner.class)
public class EventBusConfigTest {

    @SpyBean
    private ReactiveMessageSender sender;

    @Autowired
    private DomainEventBus eventBus;


    @Test
    public void domainEventBus() {
        final Mono<Void> mono = from(eventBus.emit(new DomainEvent<>("test1", "23", 11)));
        mono.block();
        verify(sender).sendWithConfirm(any(), eq("domainEvents"), anyString(), any(), true);
    }

    @Import(EventBusConfig.class)
    @SpringBootApplication
    static class App {
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }
    }
}