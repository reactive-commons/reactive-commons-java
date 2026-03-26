package org.reactivecommons.async.commons.converters.json;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.exceptions.MessageConversionException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonMessageConverterTest {

    private static final JsonMapper MAPPER = new JsonMapper();

    private JacksonMessageConverter converter;

    @BeforeEach
    void setUp() {
        converter = new JacksonMessageConverter(MAPPER) {
            @Override
            public Message toMessage(Object object) {
                byte[] bytes = jsonMapper.writeValueAsBytes(object);
                return createMessage(bytes, "application/json");
            }
        };
    }

    @Test
    void readAsyncQuery() {
        String json = "{\"resource\":\"myQuery\",\"queryData\":{\"id\":\"1\",\"name\":\"n\",\"date\":null}}";
        var msg = createMessage(json.getBytes(StandardCharsets.UTF_8), "application/json");
        var result = converter.readAsyncQuery(msg, SampleClass.class);
        assertThat(result.getResource()).isEqualTo("myQuery");
        assertThat(result.getQueryData()).isNotNull();
        assertThat(result.getQueryData().getName()).isEqualTo("n");
    }

    @Test
    void readDomainEvent() {
        String json = "{\"name\":\"myEvent\",\"eventId\":\"eid123\",\"data\":{\"id\":\"1\",\"name\":\"val\",\"date\":null}}";
        var msg = createMessage(json.getBytes(StandardCharsets.UTF_8), "application/json");
        var result = converter.readDomainEvent(msg, SampleClass.class);
        assertThat(result.getName()).isEqualTo("myEvent");
        assertThat(result.getEventId()).isEqualTo("eid123");
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().getName()).isEqualTo("val");
    }

    @Test
    void readCommand() {
        String json = "{\"name\":\"myCmd\",\"commandId\":\"cid456\",\"data\":{\"id\":\"x\",\"name\":\"v\",\"date\":null}}";
        var msg = createMessage(json.getBytes(StandardCharsets.UTF_8), "application/json");
        var result = converter.readCommand(msg, SampleClass.class);
        assertThat(result.getName()).isEqualTo("myCmd");
        assertThat(result.getCommandId()).isEqualTo("cid456");
        assertThat(result.getData()).isNotNull();
    }

    @Test
    void readValueThrowsOnInvalidJson() {
        var msg = createMessage("not-json".getBytes(StandardCharsets.UTF_8), "application/json");
        assertThatThrownBy(() -> converter.readValue(msg, SampleClass.class))
                .isInstanceOf(MessageConversionException.class)
                .hasMessageContaining("Failed to convert Message content");
    }

    @Test
    void readCommandStructure() {
        String json = "{\"name\":\"cmd\",\"commandId\":\"id\",\"data\":{\"field\":\"x\"}}";
        var msg = createMessage(json.getBytes(StandardCharsets.UTF_8), "application/json");
        var result = converter.readCommandStructure(msg);
        assertThat(result.getName()).isEqualTo("cmd");
        assertThat(result.getCommandId()).isEqualTo("id");
        assertThat(result.getData()).isNotNull();
    }

    @Test
    void readDomainEventStructure() {
        String json = "{\"name\":\"ev\",\"eventId\":\"eid\",\"data\":{\"field\":\"y\"}}";
        var msg = createMessage(json.getBytes(StandardCharsets.UTF_8), "application/json");
        var result = converter.readDomainEventStructure(msg);
        assertThat(result.getName()).isEqualTo("ev");
        assertThat(result.getEventId()).isEqualTo("eid");
    }

    @Test
    void readAsyncQueryStructure() {
        String json = "{\"resource\":\"q\",\"queryData\":{\"field\":\"z\"}}";
        var msg = createMessage(json.getBytes(StandardCharsets.UTF_8), "application/json");
        var result = converter.readAsyncQueryStructure(msg);
        assertThat(result.getResource()).isEqualTo("q");
        assertThat(result.getQueryData()).isNotNull();
    }

    private Message createMessage(byte[] body, String contentType) {
        return new Message() {
            @Override
            public String getType() {
                return "test";
            }

            @Override
            public byte[] getBody() {
                return body;
            }

            @Override
            public Properties getProperties() {
                return new Properties() {
                    @Override
                    public String getContentType() {
                        return contentType;
                    }

                    @Override
                    public long getContentLength() {
                        return body.length;
                    }

                    @Override
                    public Map<String, Object> getHeaders() {
                        return Map.of();
                    }
                };
            }
        };
    }
}
