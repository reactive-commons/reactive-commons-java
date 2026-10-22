package org.reactivecommons.async.commons.exceptions;

/**
 * The sender's publish hand-over queue was full when a new message arrived, so the publish was rejected before being
 * written to any channel. The message was definitively NOT published; re-sending it cannot produce a duplicate.
 *
 * <p>A sustained flood of these means the broker (or the socket to it) is back-pressuring the application faster than
 * it can produce messages — the hand-over queue has filled up because the broker is not draining the socket. It
 * extends {@link SendFailureNoAckException} so callers already handling a missing ACK keep handling it.
 */
public class SendFailureHandOverRejectedException extends SendFailureNoAckException {

    public SendFailureHandOverRejectedException(String message) {
        super(message);
    }

    public SendFailureHandOverRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
