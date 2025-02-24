package org.reactivecommons.async.starter.senders;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@ExtendWith(MockitoExtension.class)
class GenericDomainEventBusTest {
    public static final String DOMAIN_2 = "domain2";
    @Mock
    private DomainEventBus domainEventBus1;
    @Mock
    private DomainEventBus domainEventBus2;
    @Mock
    private CloudEvent cloudEvent;
    @Mock
    private Message rawMessage;
    @Mock
    private DomainEvent<?> domainEvent;
    private GenericDomainEventBus genericDomainEventBus;

    @BeforeEach
    void setUp() {
        ConcurrentHashMap<String, DomainEventBus> domainEventBuses = new ConcurrentHashMap<>();
        domainEventBuses.put(DEFAULT_DOMAIN, domainEventBus1);
        domainEventBuses.put(DOMAIN_2, domainEventBus2);
        genericDomainEventBus = new GenericDomainEventBus(domainEventBuses);
    }

    @Test
    void shouldEmitWithDefaultDomain() {
        // Arrange
        when(domainEventBus1.emit(domainEvent)).thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(genericDomainEventBus.emit(domainEvent));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
        verify(domainEventBus1).emit(domainEvent);
    }

    @Test
    void shouldEmitCloudEventWithDefaultDomain() {
        // Arrange
        when(domainEventBus1.emit(cloudEvent)).thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(genericDomainEventBus.emit(cloudEvent));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
        verify(domainEventBus1).emit(cloudEvent);
    }

    @Test
    void shouldEmitWithSpecificDomain() {
        // Arrange
        when(domainEventBus2.emit(domainEvent)).thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(genericDomainEventBus.emit(DOMAIN_2, domainEvent));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
        verify(domainEventBus2).emit(domainEvent);
    }

    @Test
    void shouldEmitCloudEventWithSpecificDomain() {
        // Arrange
        when(domainEventBus2.emit(cloudEvent)).thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(genericDomainEventBus.emit(DOMAIN_2, cloudEvent));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
        verify(domainEventBus2).emit(cloudEvent);
    }

    @Test
    void shouldFailWhenNoDomainFound() {
        // Arrange
        // Act
        Mono<Void> flow = Mono.from(genericDomainEventBus.emit("another", domainEvent));
        // Assert
        StepVerifier.create(flow)
                .expectError(InvalidConfigurationException.class)
                .verify();
    }

    @Test
    void shouldFailWhenNoDomainFoundWithCloudEvent() {
        // Arrange
        // Act
        Mono<Void> flow = Mono.from(genericDomainEventBus.emit("another", cloudEvent));
        // Assert
        StepVerifier.create(flow)
                .expectError(InvalidConfigurationException.class)
                .verify();
    }

    @Test
    void shouldEmitRawEventWithSpecificDomain() {
        // Arrange
        when(domainEventBus2.emit(rawMessage)).thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(genericDomainEventBus.emit(DOMAIN_2, rawMessage));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
        verify(domainEventBus2).emit(rawMessage);
    }

    @Test
    void shouldFailWhenNoDomainFoundEmittingRawEvent() {
        // Arrange
        // Act
        Mono<Void> flow = Mono.from(genericDomainEventBus.emit("another", rawMessage));
        // Assert
        StepVerifier.create(flow)
                .expectError(InvalidConfigurationException.class)
                .verify();
    }
}
