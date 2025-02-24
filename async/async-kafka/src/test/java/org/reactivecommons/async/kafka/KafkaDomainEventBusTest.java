package org.reactivecommons.async.kafka;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaDomainEventBusTest {
    @Mock
    private DomainEvent<String> domainEvent;
    @Mock
    private CloudEvent cloudEvent;
    @Mock
    private RawMessage rawMessage;
    @Mock
    private ReactiveMessageSender sender;
    @InjectMocks
    private KafkaDomainEventBus kafkaDomainEventBus;
    private final String domain = "domain";

    @Test
    void shouldEmitDomainEvent() {
        // Arrange
        when(sender.send(domainEvent)).thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(kafkaDomainEventBus.emit(domainEvent));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
    }

    @Test
    void shouldEmitCloudEvent() {
        // Arrange
        when(sender.send(cloudEvent)).thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(kafkaDomainEventBus.emit(cloudEvent));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
    }

    @Test
    void shouldEmitRawMessage() {
        // Arrange
        when(sender.send(rawMessage)).thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(kafkaDomainEventBus.emit(rawMessage));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
    }

    @Test
    void operationsShouldNotBeAbleForDomains() {
        assertThrows(UnsupportedOperationException.class, () -> kafkaDomainEventBus.emit(domain, domainEvent));
        assertThrows(UnsupportedOperationException.class, () -> kafkaDomainEventBus.emit(domain, cloudEvent));
        assertThrows(UnsupportedOperationException.class, () -> kafkaDomainEventBus.emit(domain, rawMessage));
    }
}
