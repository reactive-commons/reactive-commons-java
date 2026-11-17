package org.reactivecommons.async.kafka.communications.exceptions;

/**
 * A send was rejected at once because the sender already holds its maximum of sends waiting for their outcome
 * ({@code maxPendingSends}), e.g. while the broker is unreachable: the message was not sent, and can be sent again.
 */
public class SendQueueFullException extends RuntimeException {
    public SendQueueFullException(String message) {
        super(message);
    }
}
