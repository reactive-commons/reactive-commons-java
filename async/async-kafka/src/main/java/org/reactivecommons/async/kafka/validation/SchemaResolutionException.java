package org.reactivecommons.async.kafka.validation;

/**
 * Raised when the schema of a message could not be obtained because the schema registry could not be queried (it is
 * unreachable, it timed out or it failed), not because the message is invalid.
 * <p>
 * Unlike a {@link SchemaValidationException}, querying the registry again may succeed, so the listeners retry a
 * message that fails with it instead of discarding it. It extends {@link SchemaValidationException} so that code that
 * already handles schema failures, e.g. when an event cannot be published, keeps handling this one.
 */
public class SchemaResolutionException extends SchemaValidationException {

    public SchemaResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
