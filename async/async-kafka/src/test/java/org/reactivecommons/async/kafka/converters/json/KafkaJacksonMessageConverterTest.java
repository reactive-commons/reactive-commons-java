package org.reactivecommons.async.kafka.converters.json;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.cloudevents.jackson.JsonCloudEventData;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.commons.converters.json.ObjectMapperSupplier;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KafkaJacksonMessageConverterTest {
    private static KafkaJacksonMessageConverter converter;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() {
        ObjectMapperSupplier supplier = new DefaultObjectMapperSupplier();
        objectMapper = supplier.get();
        converter = new KafkaJacksonMessageConverter(objectMapper);
    }

    @Test
    void shouldSerializeDomainEvent() {
        // Arrange
        String id = UUID.randomUUID().toString();
        MyEvent event = new MyEvent("name", 1);
        DomainEvent<MyEvent> testEvent = new DomainEvent<>("test", id, event);
        String expectedJson = "{\"name\":\"test\",\"eventId\":\"" + id + "\",\"data\":{\"name\":\"name\",\"age\":1}}";
        // Act
        Message message = converter.toMessage(testEvent);
        // Assert
        assertEquals("test", message.getProperties().getTopic());
        assertEquals(id, message.getProperties().getKey());
        assertEquals("application/json", message.getProperties().getContentType());
        assertEquals(expectedJson, new String(message.getBody()));
    }

    @Test
    void shouldSerializeCloudEvent() throws JsonProcessingException {
        // Arrange
        String id = UUID.randomUUID().toString();
        MyEvent event = new MyEvent("name", 1);
        OffsetDateTime dateTime = OffsetDateTime.now();
        CloudEvent testCloudEvent = CloudEventBuilder.v1()
                .withId(id)
                .withSource(URI.create("https://reactivecommons.org/events"))
                .withType("test")
                .withDataContentType("application/json")
                .withTime(dateTime)
                .withData(objectMapper.writeValueAsBytes(event))
                .build();

        String expectedJson = "{\"specversion\":\"1.0\",\"id\":\"" + id +
                "\",\"source\":\"https://reactivecommons.org/events\",\"type\":\"test\"," +
                "\"datacontenttype\":\"application/json\",\"time\":\"" + dateTime +
                "\",\"data\":{\"name\":\"name\",\"age\":1}}";
        // Act
        Message message = converter.toMessage(testCloudEvent);
        // Assert
        assertEquals("test", message.getProperties().getTopic());
        assertEquals(id, message.getProperties().getKey());
        assertEquals("application/cloudevents+json", message.getProperties().getContentType());
        assertEquals(expectedJson, new String(message.getBody()));
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MyEvent {
        private String name;
        private int age;
    }
}
