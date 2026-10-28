package org.reactivecommons.async.commons.exceptions;

/**
 * The body of the message is larger than the maximum message size the broker accepts, so the publishing was rejected
 * before being written to any channel. The message was definitively NOT published; re-sending it unchanged fails the
 * same way.
 *
 * <p>Rejecting it in the client matters because the broker does not reject an oversized message alone: it closes the
 * whole channel ({@code 406 PRECONDITION_FAILED}), failing the other messages published on it. It extends
 * {@link SendFailureNoAckException} so callers already handling a missing ACK keep handling it.
 */
public class SendFailureMessageTooLargeException extends SendFailureNoAckException {

    public SendFailureMessageTooLargeException(String message) {
        super(message);
    }
}
