package org.reactivecommons.async.rabbit;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RabbitDomainEventBusTest {
    @Mock
    private DomainEvent<String> domainEvent;
    @Mock
    private CloudEvent cloudEvent;
    @Mock
    private RawMessage rawMessage;
    @Mock
    private ReactiveMessageSender sender;
    private RabbitDomainEventBus rabbitDomainEventBus;
    private final String domain = "domain";

    @BeforeEach
    void setUp() {
        rabbitDomainEventBus = new RabbitDomainEventBus(sender, "exchange");
    }

    @Test
    void shouldEmitDomainEvent() {
        // Arrange
        when(domainEvent.getName()).thenReturn("event");
        when(sender.sendWithConfirm(any(DomainEvent.class), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(rabbitDomainEventBus.emit(domainEvent));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
    }

    @Test
    void shouldEmitCloudEvent() {
        // Arrange
        when(cloudEvent.getType()).thenReturn("event");
        when(sender.sendWithConfirm(any(CloudEvent.class), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(rabbitDomainEventBus.emit(cloudEvent));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
    }

    @Test
    void shouldEmitRawMessage() {
        // Arrange
        when(rawMessage.getType()).thenReturn("event");
        when(sender.sendWithConfirm(any(RawMessage.class), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(rabbitDomainEventBus.emit(rawMessage));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
    }

    @Test
    void operationsShouldNotBeAbleForDomains() {
        assertThrows(UnsupportedOperationException.class, () -> rabbitDomainEventBus.emit(domain, domainEvent));
        assertThrows(UnsupportedOperationException.class, () -> rabbitDomainEventBus.emit(domain, cloudEvent));
        assertThrows(UnsupportedOperationException.class, () -> rabbitDomainEventBus.emit(domain, rawMessage));
    }
}
