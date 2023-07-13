package org.reactivecommons.async.rabbit.converters.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.commons.communications.Message;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class JacksonCloudEventMessageConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JacksonCloudEventMessageConverter converter = new JacksonCloudEventMessageConverter(objectMapper);

    @Test
    void readAsyncQuery() throws JsonProcessingException {
        Date date = new Date();
        CloudEvent query = CloudEventBuilder.v1() //
                .withId(UUID.randomUUID().toString()) //
                .withSource(URI.create("https://spring.io/foos"))//
                .withType("query")
                .withData("application/json", CloudEventBuilderExt.asBytes(new SampleClass("35", "name1", date)))
                .build();
        final Message message = converter.toMessage(new AsyncQuery<>(query.getType(), query));
        final AsyncQuery<CloudEvent> value = converter.readAsyncQuery(message, CloudEvent.class);
        byte[] bytes = value.getQueryData().getData().toBytes();
        String stringData = new String(bytes, StandardCharsets.UTF_8);
        SampleClass result = objectMapper.readValue(stringData, SampleClass.class);
        assertThat(result)
                .extracting(SampleClass::getId, SampleClass::getName, SampleClass::getDate)
                .containsExactly("35", "name1", date);

    }

    @Test
    void readDomainEvent() throws JsonProcessingException {
        Date date = new Date();
        CloudEvent event = CloudEventBuilder.v1() //
                .withId(UUID.randomUUID().toString()) //
                .withSource(URI.create("https://spring.io/foos"))//
                .withType("event")
                .withData("application/json", CloudEventBuilderExt.asBytes(new SampleClass("35", "name1", date)))
                .build();
        final Message message = converter.toMessage(new DomainEvent<>(event.getType(), event.getId(), event));
        final DomainEvent<CloudEvent> value = converter.readDomainEvent(message, CloudEvent.class);
        byte[] bytes = value.getData().getData().toBytes();
        String stringData = new String(bytes, StandardCharsets.UTF_8);
        SampleClass result = objectMapper.readValue(stringData, SampleClass.class);
        assertThat(result)
                .extracting(SampleClass::getId, SampleClass::getName, SampleClass::getDate)
                .containsExactly("35", "name1", date);
    }

    @Test
    void readCommand() throws JsonProcessingException {
        Date date = new Date();
        CloudEvent command = CloudEventBuilder.v1() //
                .withId(UUID.randomUUID().toString()) //
                .withSource(URI.create("https://spring.io/foos"))//
                .withType("command")
                .withData("application/json", CloudEventBuilderExt.asBytes(new SampleClass("35", "name1", date)))
                .build();
        final Message message = converter.toMessage(new Command<>(command.getType(), command.getId(), command));
        final Command<CloudEvent> value = converter.readCommand(message, CloudEvent.class);
        byte[] bytes = value.getData().getData().toBytes();
        String stringData = new String(bytes, StandardCharsets.UTF_8);
        SampleClass result = objectMapper.readValue(stringData, SampleClass.class);
        assertThat(result)
                .extracting(SampleClass::getId, SampleClass::getName, SampleClass::getDate)
                .containsExactly("35", "name1", date);
    }
    @Test
    void toMessage() {
        final Message message = converter.toMessage(new SampleClass("42", "Daniel", new Date()));
        assertThat(new String(message.getBody())).contains("42").contains("Daniel");
    }

    @Test
    void toMessageWhenDataIsNull() throws IOException {
        final Message message = converter.toMessage(null);

        final JsonNode jsonNode = objectMapper.readTree(message.getBody());
        assertThat(jsonNode.isNull()).isTrue();
    }

    @Test
    void toMessageWhenDataIsEmpty() throws IOException {
        final Message message = converter.toMessage("");

        final JsonNode jsonNode = objectMapper.readTree(message.getBody());
        assertThat(jsonNode.asText()).isEmpty();
    }

    @Test
    void readValue() {
        Date date = new Date();
        final Message message = converter.toMessage(new SampleClass("35", "name1", date));
        final SampleClass value = converter.readValue(message, SampleClass.class);
        assertThat(value).extracting(SampleClass::getId, SampleClass::getName, SampleClass::getDate)
                .containsExactly("35", "name1", date);
    }

    @Test
    void readValueString() {
        final Message message = converter.toMessage("Hi!");
        final String value = converter.readValue(message, String.class);
        assertThat(value).isEqualTo("Hi!");
    }

    @Test
    void shouldConvertToCommandStructure() {
        final SampleClass data = new SampleClass("35", "name1", new Date());
        final Message message = converter.toMessage(new Command<>("cmd.name", "42", data));
        final Command<Object> command = converter.readCommandStructure(message);

        assertThat(command.getData()).isInstanceOf(JsonNode.class);
        assertThat(command.getName()).isEqualTo("cmd.name");
    }

    @Test
    void shouldConvertToDomainEventStructure() {
        final SampleClass data = new SampleClass("35", "name1", new Date());
        final Message message = converter.toMessage(new DomainEvent<>("event.name", "42", data));
        final DomainEvent<Object> event = converter.readDomainEventStructure(message);

        assertThat(event.getData()).isInstanceOf(JsonNode.class);
        assertThat(event.getName()).isEqualTo("event.name");
        final JsonNode jsonNode = (JsonNode) event.getData();
        assertThat(jsonNode.findValue("name").asText()).isEqualTo("name1");
    }

    @Test
    void shouldConvertToQueryStructure() {
        final SampleClass data = new SampleClass("35", "sample1", new Date());
        final Message message = converter.toMessage(new AsyncQuery<>("query.name", data));
        final AsyncQuery<Object> query = converter.readAsyncQueryStructure(message);

        assertThat(query.getQueryData()).isInstanceOf(JsonNode.class);
        assertThat(query.getResource()).isEqualTo("query.name");
        final JsonNode jsonNode = (JsonNode) query.getQueryData();
        assertThat(jsonNode.findValue("name").asText()).isEqualTo("sample1");
    }

    @Test
    void shouldNotFailWithTilde() {
        // Arrange
        final String name = "example with word containing tilde áéíóúñ";
        final SampleClass data = new SampleClass("35", name, new Date());
        final Message message = converter.toMessage(new AsyncQuery<>("query.name", data));
        // Act
        final AsyncQuery<Object> query = converter.readAsyncQueryStructure(message);
        // Assert
        assertThat(query.getQueryData()).isInstanceOf(JsonNode.class);
        assertThat(query.getResource()).isEqualTo("query.name");
        final JsonNode jsonNode = (JsonNode) query.getQueryData();
        assertThat(jsonNode.findValue("name").asText()).isEqualTo(name);
    }
}
