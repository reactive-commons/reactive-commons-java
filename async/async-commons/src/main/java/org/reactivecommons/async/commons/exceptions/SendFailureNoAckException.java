package org.reactivecommons.async.commons.exceptions;

public class SendFailureNoAckException extends RuntimeException {
    public SendFailureNoAckException(String message) {
        super(message);
    }
}
