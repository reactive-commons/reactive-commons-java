package org.reactivecommons.async.parent.exceptions;

public class SendFailureNoAckException extends RuntimeException {
    public SendFailureNoAckException(String message) {
        super(message);
    }
}
