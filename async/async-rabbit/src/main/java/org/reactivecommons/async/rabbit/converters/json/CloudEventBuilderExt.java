package org.reactivecommons.async.rabbit.converters.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.util.Objects;

@UtilityClass
public class CloudEventBuilderExt {
    private static final ObjectMapper mapper = new ObjectMapper();

    @SneakyThrows
    public static byte[] asBytes(Object object) {
        return mapper.writeValueAsBytes(object);
    }

    @SneakyThrows
    public static <T> T fromCloudEventData(CloudEvent cloudEvent, Class<T> classValue) {
        return mapper.readValue(Objects.requireNonNull(cloudEvent.getData()).toBytes(), classValue);
    }
}
