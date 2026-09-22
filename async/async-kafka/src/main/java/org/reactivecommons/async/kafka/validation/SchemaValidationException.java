package org.reactivecommons.async.kafka.validation;

/**
 * Raised when a message payload does not conform to the schema registered for its topic.
 * <p>
 * On the consumer side this error is propagated per message, so the configured retry and
 * DLQ strategy of the listener applies. On the producer side it prevents the record from
 * being published.
 */
public class SchemaValidationException extends RuntimeException {

    public SchemaValidationException(String message) {
        super(message);
    }

    public SchemaValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
