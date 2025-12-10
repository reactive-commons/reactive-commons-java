package org.reactivecommons.async.commons.converters.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.reactivecommons.cloudevents.jackson.JsonFormat;

public class DefaultObjectMapperSupplier implements ObjectMapperSupplier {

    @Override
    public ObjectMapper get() {
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.findAndRegisterModules();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.registerModule(JsonFormat.getCloudEventJacksonModule());
        return objectMapper;
    }

}
