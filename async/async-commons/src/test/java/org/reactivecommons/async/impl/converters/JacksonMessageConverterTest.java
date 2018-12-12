package org.reactivecommons.async.impl.converters;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.Test;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.async.impl.communications.Message;

import java.io.IOException;
import java.util.Date;

public class JacksonMessageConverterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void toMessage() {
        JacksonMessageConverter converter = new JacksonMessageConverter(objectMapper);
        final Message message = converter.toMessage(new SampleClass("42", "Daniel", new Date()));
        assertThat(new String(message.getBody())).contains("42").contains("Daniel");
    }

    @Test
    public void toMessageWhenDataIsNull() throws IOException {
        JacksonMessageConverter converter = new JacksonMessageConverter(new ObjectMapper());
        final Message message = converter.toMessage(null);

        final JsonNode jsonNode = objectMapper.readTree(message.getBody());
        assertThat(jsonNode.isNull()).isTrue();
    }

    @Test
    public void toMessageWhenDataIsEmpty() throws IOException {
        JacksonMessageConverter converter = new JacksonMessageConverter(new ObjectMapper());
        final Message message = converter.toMessage("");

        final JsonNode jsonNode = objectMapper.readTree(message.getBody());
        assertThat(jsonNode.asText()).isEmpty();
    }

    @RequiredArgsConstructor
    @Getter
    private static class SampleClass {
        private final String id;
        private final String name;
        private final Date date;
    }
}