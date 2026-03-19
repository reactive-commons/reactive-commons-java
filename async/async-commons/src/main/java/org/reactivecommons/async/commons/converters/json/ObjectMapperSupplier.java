package org.reactivecommons.async.commons.converters.json;

import tools.jackson.databind.json.JsonMapper;

import java.util.function.Supplier;

public interface ObjectMapperSupplier extends Supplier<JsonMapper> {
}
