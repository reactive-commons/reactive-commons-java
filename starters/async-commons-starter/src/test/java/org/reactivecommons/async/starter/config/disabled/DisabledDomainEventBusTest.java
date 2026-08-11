package org.reactivecommons.async.starter.config.disabled;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.RawMessage;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class DisabledDomainEventBusTest {

    private DisabledDomainEventBus eventBus;
    private static final String ERROR_MESSAGE = "sendEvents feature is disabled in ReactiveCommonsFeatures. " +
            "Set sendEvents=true to publish domain events.";

    @Mock
    private CloudEvent cloudEvent;

    @Mock
    private DomainEvent<String> domainEvent;

    @Mock
    private RawMessage rawMessage;

    @BeforeEach
    void setUp() {
        eventBus = new DisabledDomainEventBus();
    }

    @Test
    void shouldThrowErrorWhenEmittingDomainEvent() {
        StepVerifier.create(eventBus.emit(domainEvent))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenEmittingDomainEventWithDomain() {
        StepVerifier.create(eventBus.emit("domain", domainEvent))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenEmittingCloudEvent() {
        StepVerifier.create(eventBus.emit(cloudEvent))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenEmittingCloudEventWithDomain() {
        StepVerifier.create(eventBus.emit("domain", cloudEvent))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenEmittingRawMessage() {
        StepVerifier.create(eventBus.emit(rawMessage))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }

    @Test
    void shouldThrowErrorWhenEmittingRawMessageWithDomain() {
        StepVerifier.create(eventBus.emit("domain", rawMessage))
                .expectErrorMatches(error -> error instanceof IllegalStateException &&
                        error.getMessage().equals(ERROR_MESSAGE))
                .verify();
    }
}
