package org.reactivecommons.async.rabbit;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * The typed outcome of the publish tells the emitter whether emitting the event again is safe (not published) or
     * may duplicate it (unknown outcome): it must reach the emitter as it is, from every kind of event.
     */
    @Test
    void aTypedPublishOutcomeReachesTheEmitterAsItIs() {
        SendConfirmUnknownException unknown = new SendConfirmUnknownException("no confirmation received");
        when(domainEvent.getName()).thenReturn("event");
        when(cloudEvent.getType()).thenReturn("event");
        when(rawMessage.getType()).thenReturn("event");
        when(sender.sendWithConfirm(any(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(Mono.error(unknown));

        StepVerifier.create(Mono.from(rabbitDomainEventBus.emit(domainEvent)))
                .verifyErrorSatisfies(error -> assertThat(error).isSameAs(unknown));
        StepVerifier.create(Mono.from(rabbitDomainEventBus.emit(cloudEvent)))
                .verifyErrorSatisfies(error -> assertThat(error).isSameAs(unknown));
        StepVerifier.create(Mono.from(rabbitDomainEventBus.emit(rawMessage)))
                .verifyErrorSatisfies(error -> assertThat(error).isSameAs(unknown));
    }

    @Test
    void anyOtherFailureIsReportedAsASendFailureOfTheEvent() {
        IllegalStateException failure = new IllegalStateException("not serializable");
        when(domainEvent.getName()).thenReturn("event");
        when(sender.sendWithConfirm(any(DomainEvent.class), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(Mono.error(failure));

        StepVerifier.create(Mono.from(rabbitDomainEventBus.emit(domainEvent)))
                .verifyErrorSatisfies(error -> assertThat(error)
                        .hasMessage("Event send failure: event")
                        .hasCause(failure));
    }

    @Test
    void operationsShouldNotBeAbleForDomains() {
        assertThrows(UnsupportedOperationException.class, () -> rabbitDomainEventBus.emit(domain, domainEvent));
        assertThrows(UnsupportedOperationException.class, () -> rabbitDomainEventBus.emit(domain, cloudEvent));
        assertThrows(UnsupportedOperationException.class, () -> rabbitDomainEventBus.emit(domain, rawMessage));
    }
}
