package org.reactivecommons.async.commons.exceptions;

/**
 * The outcome of a publication is unknown: the message was written to the broker connection, but the channel or the
 * connection failed (or the confirmation did not arrive in time) before the broker confirmed it. The message may or
 * may not have been stored by the broker, so re-sending it can produce a duplicate (at-least-once delivery).
 *
 * <p>It extends {@link SendFailureNoAckException} so callers already handling a missing ACK keep handling it.
 */
public class SendConfirmUnknownException extends SendFailureNoAckException {

    public SendConfirmUnknownException(String message) {
        super(message);
    }

    public SendConfirmUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
