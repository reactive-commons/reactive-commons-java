package org.reactivecommons.async.impl.converters.json;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Supplier;

public interface ObjectMapperSupplier extends Supplier<ObjectMapper> {
}
