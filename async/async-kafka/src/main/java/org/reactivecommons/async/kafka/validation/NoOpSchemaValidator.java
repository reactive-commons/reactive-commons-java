package org.reactivecommons.async.kafka.validation;

import org.apache.kafka.common.header.Headers;

/**
 * Default {@link SchemaValidator} that accepts every payload, keeping schema validation opt-in.
 */
public final class NoOpSchemaValidator implements SchemaValidator {

    public static final NoOpSchemaValidator INSTANCE = new NoOpSchemaValidator();

    @Override
    public void validateOutbound(String topic, byte[] payload, Headers headers) {
        // no validation by default
    }

    @Override
    public void validateInbound(String topic, byte[] payload, Headers headers) {
        // no validation by default
    }
}
