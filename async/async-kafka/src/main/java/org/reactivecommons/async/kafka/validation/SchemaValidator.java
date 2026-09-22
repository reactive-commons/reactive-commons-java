package org.reactivecommons.async.kafka.validation;

import org.apache.kafka.common.header.Headers;

/**
 * Extension point to validate the raw payload of a Kafka record against a schema.
 * <p>
 * The default implementation is {@link NoOpSchemaValidator}, so validation is opt-in.
 */
public interface SchemaValidator {

    /**
     * Validates a payload before it is published.
     * <p>
     * Implementations may also enrich {@code headers} with the schema coordinates so that
     * downstream consumers can resolve the very same schema version.
     *
     * @param topic   destination topic
     * @param payload serialized message body
     * @param headers mutable headers of the record about to be sent
     * @throws SchemaValidationException when the payload does not conform to the schema
     */
    void validateOutbound(String topic, byte[] payload, Headers headers);

    /**
     * Validates a payload just after it is received and before it reaches the handler.
     *
     * @param topic   source topic
     * @param payload received message body
     * @param headers headers of the received record
     * @throws SchemaValidationException when the payload does not conform to the schema
     */
    void validateInbound(String topic, byte[] payload, Headers headers);
}
