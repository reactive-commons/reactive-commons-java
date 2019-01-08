package org.reactivecommons.async.impl.config.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.from;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.config.EventBusConfig;
import org.reactivecommons.async.impl.config.props.BrokerConfigPropsTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ExchangeSpecification;

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
        verify(sender).sendWithConfirm(any(), eq("domainEvents"), anyString(), any());
    }

    @Import(EventBusConfig.class)
    @SpringBootApplication
    static class App {
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }
    }
}