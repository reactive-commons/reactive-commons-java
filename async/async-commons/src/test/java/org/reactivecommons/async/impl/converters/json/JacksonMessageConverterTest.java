package org.reactivecommons.async.impl.converters.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.reactivecommons.async.impl.communications.Message;

import java.io.IOException;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

public class JacksonMessageConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JacksonMessageConverter converter = new JacksonMessageConverter(objectMapper);

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

}