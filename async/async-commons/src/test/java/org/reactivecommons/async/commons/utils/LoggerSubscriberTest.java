package org.reactivecommons.async.commons.utils;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.SignalType;


public class LoggerSubscriberTest {

    private final LoggerSubscriber<String> subscriber = new LoggerSubscriber<>("testFlow");

    @Test
    public void shouldPrintOnCancelMessage() {
        subscriber.hookOnCancel();
    }

    @Test
    public void shouldPrintOnErrorMessage() {
        subscriber.hookOnError(new RuntimeException());
    }

    @Test
    public void shouldPrintOnFinallyMessage() {
        subscriber.hookFinally(SignalType.ON_ERROR);
    }

    @Test
    public void shouldPrintOnCompleteMessage() {
        subscriber.hookOnComplete();
    }

}