package org.reactivecommons.async.commons.exceptions;

/**
 * No handler is registered for the message (e.g. a binding left by a version that had one): retrying it can never
 * succeed, so a listener discards it instead of retrying it.
 */
public class MissingHandlerException extends RuntimeException {

    public MissingHandlerException(String kind, String name) {
        super("No handler registered for " + kind + " " + name);
    }
}
