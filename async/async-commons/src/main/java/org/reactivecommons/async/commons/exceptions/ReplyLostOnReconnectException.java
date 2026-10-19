package org.reactivecommons.async.commons.exceptions;

/**
 * The reply listener was restarted (its channel or connection dropped) while a reply was in flight. The exclusive +
 * autoDelete reply queue was recreated empty on reconnect, so any reply the broker had buffered for the previous
 * instance is lost. Callers see this instead of waiting out {@code sendTimeout}.
 *
 * <p>It extends {@link SendFailureNoAckException} because, from the caller's point of view, the request/reply
 * exchange failed and the response is not coming. The request itself may or may not have been handled by the
 * server: the semantics match {@link SendConfirmUnknownException} on the request path - callers must be
 * idempotent or deduplicate replies with their correlation ID.
 */
public class ReplyLostOnReconnectException extends SendFailureNoAckException {

    public ReplyLostOnReconnectException(String message) {
        super(message);
    }

    public ReplyLostOnReconnectException(String message, Throwable cause) {
        super(message, cause);
    }
}
