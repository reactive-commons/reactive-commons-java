package org.reactivecommons.async.kafka;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.RawMessage;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

    /**
     * The event bus of the discard notifier adds to a discarded event the DLQ headers that its listener put in the
     * subscriber context.
     */
    @Test
    void shouldAddTheDlqHeadersOfTheSubscriberContextToTheEvent() {
        // Arrange
        KafkaDomainEventBus discardBus = new KafkaDomainEventBus(sender,
                new KafkaJacksonMessageConverter(new DefaultObjectMapperSupplier().get()));
        DomainEvent<String> discarded = new DomainEvent<>("orders.created.dlq", "evt-1", "data");
        when(sender.send(any())).thenReturn(Mono.empty());
        Map<String, Object> dlqHeaders = Map.of(KafkaDlqHeaders.ORIGINAL_TOPIC, "orders.created");
        // Act
        Mono<Void> flow = Mono.from(discardBus.emit(discarded))
                .contextWrite(context -> KafkaDlqHeaders.into(context, dlqHeaders));
        // Assert
        StepVerifier.create(flow).verifyComplete();
        ArgumentCaptor<KafkaMessage> sent = ArgumentCaptor.forClass(KafkaMessage.class);
        verify(sender).send(sent.capture());
        assertThat(sent.getValue().getProperties().getTopic()).isEqualTo("orders.created.dlq");
        assertThat(sent.getValue().getProperties().getKey()).isEqualTo("evt-1");
        assertThat(sent.getValue().getProperties().getHeaders())
                .containsEntry(KafkaDlqHeaders.ORIGINAL_TOPIC, "orders.created")
                .containsKey("content-type");
    }

    @Test
    void shouldSendTheEventAsItIsWithoutDlqHeaders() {
        // Arrange
        KafkaDomainEventBus discardBus = new KafkaDomainEventBus(sender,
                new KafkaJacksonMessageConverter(new DefaultObjectMapperSupplier().get()));
        when(sender.send(domainEvent)).thenReturn(Mono.empty());
        // Act
        Mono<Void> flow = Mono.from(discardBus.emit(domainEvent));
        // Assert
        StepVerifier.create(flow).verifyComplete();
        verify(sender).send(domainEvent);
    }

    /**
     * KC-27: an unsupported operation fails through {@code onError}, so the error handlers of the caller see it;
     * calling it does not throw.
     */
    @Test
    void operationsShouldNotBeAbleForDomains() {
        StepVerifier.create(assertDoesNotThrow(() -> kafkaDomainEventBus.emit(domain, domainEvent)))
                .expectError(UnsupportedOperationException.class)
                .verify();
        StepVerifier.create(assertDoesNotThrow(() -> kafkaDomainEventBus.emit(domain, cloudEvent)))
                .expectError(UnsupportedOperationException.class)
                .verify();
        StepVerifier.create(assertDoesNotThrow(() -> kafkaDomainEventBus.emit(domain, rawMessage)))
                .expectError(UnsupportedOperationException.class)
                .verify();
    }
}
