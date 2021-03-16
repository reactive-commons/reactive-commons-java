package org.reactivecommons.async.parent.exceptions;

public class MessageConversionException extends RuntimeException {

    public MessageConversionException(String message, Throwable cause) {
        super(message, cause);
    }

    public MessageConversionException(String message) {
        super(message);
    }

    public MessageConversionException(Exception e) {
        super(e);
    }
}
