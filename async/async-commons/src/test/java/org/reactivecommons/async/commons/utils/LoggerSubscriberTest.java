package org.reactivecommons.async.commons.utils;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.SignalType;


public class LoggerSubscriberTest {

    private final LoggerSubscriber<String> subscriber = new LoggerSubscriber<>("testFlow");

    @Test
    void shouldPrintOnCancelMessage() {
        subscriber.hookOnCancel();
    }

    @Test
    void shouldPrintOnErrorMessage() {
        subscriber.hookOnError(new RuntimeException());
    }

    @Test
    void shouldPrintOnFinallyMessage() {
        subscriber.hookFinally(SignalType.ON_ERROR);
    }

    @Test
    void shouldPrintOnCompleteMessage() {
        subscriber.hookOnComplete();
    }

}