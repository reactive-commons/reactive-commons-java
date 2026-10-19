package org.reactivecommons.async.commons.exceptions;

/**
 * The broker returned the message because it could not be routed (mandatory publish with no queue bound to the
 * routing key). The message was definitively NOT delivered, so re-sending it cannot produce a duplicate.
 *
 * <p>It extends {@link SendFailureNoAckException} so callers already handling a missing ACK keep handling it.
 */
public class SendFailureReturnedException extends SendFailureNoAckException {

    public SendFailureReturnedException(String message) {
        super(message);
    }

    public SendFailureReturnedException(String message, Throwable cause) {
        super(message, cause);
    }
}
