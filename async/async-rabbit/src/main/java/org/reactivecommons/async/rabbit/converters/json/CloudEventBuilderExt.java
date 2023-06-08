package org.reactivecommons.async.rabbit.converters.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

@UtilityClass
public class CloudEventBuilderExt {
    private static final ObjectMapper mapper = new ObjectMapper();

    @SneakyThrows
    public static byte[] asBytes(Object object) {
        return mapper.writeValueAsBytes(object);
    }
}
