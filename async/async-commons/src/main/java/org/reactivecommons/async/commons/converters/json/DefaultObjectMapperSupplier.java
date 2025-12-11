package org.reactivecommons.async.commons.converters.json;

import io.cloudevents.jackson.JsonFormat;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class DefaultObjectMapperSupplier implements ObjectMapperSupplier {

    @Override
    public ObjectMapper get() {
        return JsonMapper.builder()
                .addModule(JsonFormat.getCloudEventJacksonModule())
                .findAndAddModules()
                .build();
    }

}
