package org.reactivecommons.async.commons.converters.json;

import io.cloudevents.CloudEvent;
import io.cloudevents.CloudEventData;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class CloudEventBuilderExt {
    private static final JsonMapper mapper = new JsonMapper();

    @SneakyThrows
    public static byte[] asBytes(Object object) {
        return mapper.writeValueAsBytes(object);
    }

    public static CloudEventData asCloudEventData(Object object) {
        return () -> asBytes(object);
    }

    @SneakyThrows
    public static <T> T fromCloudEventData(CloudEvent cloudEvent, Class<T> classValue) {
        return mapper.readValue(Objects.requireNonNull(cloudEvent.getData()).toBytes(), classValue);
    }
}
