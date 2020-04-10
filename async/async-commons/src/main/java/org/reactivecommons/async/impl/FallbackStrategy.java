package org.reactivecommons.async.impl;

public enum FallbackStrategy {
    FAST_RETRY("ATTENTION!! Fast retry message to same Queue: %s"),
    DEFINITIVE_DISCARD("ATTENTION!! DEFINITIVE DISCARD!! of the message: %s"),
    RETRY_DLQ("ATTENTION!! Sending message to Retry DLQ: %s");

    public final String message;
    FallbackStrategy(String message){
        this.message = message;
    }
}
