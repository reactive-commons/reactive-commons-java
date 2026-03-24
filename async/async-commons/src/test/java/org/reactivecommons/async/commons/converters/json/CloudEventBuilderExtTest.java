package org.reactivecommons.async.commons.converters.json;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CloudEventBuilderExtTest {

    @Test
    void asBytesSerializesObject() {
        byte[] bytes = CloudEventBuilderExt.asBytes(new TestData("hello"));
        assertThat(bytes).isNotEmpty();
        var json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).contains("hello");
    }

    @Test
    void asCloudEventDataWrapsObject() {
        var data = CloudEventBuilderExt.asCloudEventData(new TestData("test"));
        assertThat(data.toBytes()).isNotEmpty();
    }

    @Test
    void fromCloudEventDataDeserializes() {
        byte[] json = "{\"value\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1")
                .withType("test")
                .withSource(URI.create("/test"))
                .withData("application/json", json)
                .build();

        TestData result = CloudEventBuilderExt.fromCloudEventData(event, TestData.class);
        assertThat(result.getValue()).isEqualTo("hello");
    }

    @Setter
    @Getter
    @NoArgsConstructor
    public static class TestData {
        private String value;

        public TestData(String value) {
            this.value = value;
        }

    }
}
