package org.reactivecommons.async.commons.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import static org.assertj.core.api.Assertions.assertThat;


class LoggerSubscriberTest {

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

    /**
     * Cancelling the flow is the application stopping it: logged at DEBUG, while its completion stays a warning.
     */
    @Test
    void aCancelledFlowIsLoggedAtDebugAndACompletedOneAtWarn() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggerSubscriber.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            Flux.never().subscribeWith(new LoggerSubscriber<>("cancelled")).dispose();
            Flux.empty().subscribeWith(new LoggerSubscriber<>("completed"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        assertThat(appender.list).filteredOn(event -> event.getFormattedMessage().startsWith("cancelled"))
                .isNotEmpty().allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
        assertThat(appender.list).filteredOn(event -> event.getFormattedMessage().startsWith("completed"))
                .extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.DEBUG);
    }
}
