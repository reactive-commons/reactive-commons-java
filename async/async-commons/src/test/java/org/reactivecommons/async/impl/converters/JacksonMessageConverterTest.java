package org.reactivecommons.async.impl.converters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.impl.RabbitMessage;
import org.reactivecommons.async.impl.communications.Message;

import java.io.IOException;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

public class JacksonMessageConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JacksonMessageConverter converter = new JacksonMessageConverter();

    @Test
    public void toMessage() {
        final Message message = converter.toMessage(new SampleClass("42", "Daniel", new Date()));
        assertThat(new String(message.getBody())).contains("42").contains("Daniel");
    }

    @Test
    public void toMessageWhenDataIsNull() throws IOException {
        final Message message = converter.toMessage(null);

        final JsonNode jsonNode = objectMapper.readTree(message.getBody());
        assertThat(jsonNode.isNull()).isTrue();
    }

    @Test
    public void toMessageWhenDataIsEmpty() throws IOException {
        final Message message = converter.toMessage("");

        final JsonNode jsonNode = objectMapper.readTree(message.getBody());
        assertThat(jsonNode.asText()).isEmpty();
    }

    @Test
    public void shouldConvertWithUnknownProperties() throws JsonProcessingException {

        final String value = objectMapper.writeValueAsString(new Command<>("test", "42",
            new SampleClassExtra("23", "one", new Date(), 45l)));

        final Command<SampleClass> command = converter.readCommand(new RabbitMessage(value.getBytes(),
            null), SampleClass.class);

        assertThat(command.getData()).extracting(SampleClass::getId, SampleClass::getName)
            .containsExactly("23", "one");
    }

    @Test
    public void readValue() {
        Date date = new Date();
        final Message message = converter.toMessage(new SampleClass("35", "name1", date));
        final SampleClass value = converter.readValue(message, SampleClass.class);
        assertThat(value).extracting(SampleClass::getId, SampleClass::getName, SampleClass::getDate)
            .containsExactly("35", "name1", date);
    }

    @Test
    public void readValueString() {
        final Message message = converter.toMessage("Hi!");
        final String value = converter.readValue(message, String.class);
        assertThat(value).isEqualTo("Hi!");
    }

    @RequiredArgsConstructor
    @Getter
    private static class SampleClass {
        private final String id;
        private final String name;
        private final Date date;
    }

    @Getter
    private static class SampleClassExtra extends SampleClass {

        public SampleClassExtra(String id, String name, Date date, Long newProp) {
            super(id, name, date);
            this.newProp = newProp;
        }

        private final Long newProp;
    }
}