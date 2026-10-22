package org.reactivecommons.async.commons.exceptions;

/**
 * The broker signalled a {@code connection.blocked} alarm (memory or disk watermark reached) and the sender is
 * configured to fail fast while blocked. The message was definitively NOT published, so re-sending it (after the
 * alarm clears) cannot produce a duplicate.
 *
 * <p>It extends {@link SendFailureNoAckException} so callers already handling a missing ACK keep handling it.
 */
public class SendFailureBrokerBlockedException extends SendFailureNoAckException {

    public SendFailureBrokerBlockedException(String message) {
        super(message);
    }

    public SendFailureBrokerBlockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
