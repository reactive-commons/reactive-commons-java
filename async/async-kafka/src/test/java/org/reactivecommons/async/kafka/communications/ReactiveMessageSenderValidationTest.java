package org.reactivecommons.async.kafka.communications;

import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import org.reactivecommons.async.kafka.validation.SchemaValidationException;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import reactor.core.publisher.Flux;
import reactor.kafka.sender.KafkaSender;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@ExtendWith(MockitoExtension.class)
class ReactiveMessageSenderValidationTest {

    private static final byte[] PAYLOAD = "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8);

    @Mock
    private KafkaSender<String, byte[]> kafkaSender;
    @Mock
    private MessageConverter messageConverter;
    @Mock
    private TopologyCreator topologyCreator;
    @Mock
    private SchemaValidator schemaValidator;

    private ReactiveMessageSender sender;

    @BeforeEach
    void setUp() {
        when(kafkaSender.send(any(Flux.class))).thenReturn(Flux.never());
        KafkaMessage.KafkaMessageProperties properties = new KafkaMessage.KafkaMessageProperties();
        properties.setTopic("topic");
        properties.setKey("key");
        properties.setHeaders(Map.of("contentType", "application/json"));
        KafkaMessage message = new KafkaMessage(PAYLOAD, properties, "event");
        when(messageConverter.toMessage(any())).thenReturn(message);
        sender = new ReactiveMessageSender(kafkaSender, messageConverter, topologyCreator, schemaValidator);
    }

    @Test
    void shouldValidateEveryPublishedPayload() {
        DomainEvent<String> event = new DomainEvent<>("name", "id", "value");

        StepVerifier.create(sender.send(event))
                .expectSubscription()
                .thenAwait(Duration.ofMillis(200))
                .thenCancel()
                .verify();

        ArgumentCaptor<Headers> captor = ArgumentCaptor.forClass(Headers.class);
        verify(schemaValidator, times(1)).validateOutbound(anyString(), any(), captor.capture());
        assertThat(captor.getValue().lastHeader("contentType")).isNotNull();
    }

    @Test
    void shouldNotPublishWhenSchemaValidationFails() {
        doThrow(new SchemaValidationException("invalid payload"))
                .when(schemaValidator).validateOutbound(anyString(), any(), any());
        DomainEvent<String> event = new DomainEvent<>("name", "id", "value");

        StepVerifier.create(sender.send(event))
                .expectError(SchemaValidationException.class)
                .verify();
    }
}
