package org.reactivecommons.async.commons.converters.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.jackson.JsonFormat;

public class DefaultObjectMapperSupplier implements ObjectMapperSupplier {

    @Override
    public ObjectMapper get() {
        final ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.findAndRegisterModules();
        objectMapper.registerModule(JsonFormat.getCloudEventJacksonModule()); // TODO: Review if this is necessary
        return objectMapper;
    }

}
