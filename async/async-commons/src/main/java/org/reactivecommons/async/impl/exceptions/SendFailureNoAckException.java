package org.reactivecommons.async.impl.exceptions;

public class SendFailureNoAckException extends RuntimeException {
    public SendFailureNoAckException(String message) {
        super(message);
    }
}
