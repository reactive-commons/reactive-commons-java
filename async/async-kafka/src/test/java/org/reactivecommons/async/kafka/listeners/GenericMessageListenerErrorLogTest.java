package org.reactivecommons.async.kafka.listeners;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.FallbackStrategy;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * KC-16: a failed message is logged without its body nor the values of its headers, which may carry personal data or
 * credentials; they are only logged at {@code DEBUG}.
 */
class GenericMessageListenerErrorLogTest {

    private static final String BODY = "{\"card\":\"4111-SECRET-BODY\"}";
    private static final String HEADER_VALUE = "Bearer header-secret-value";

    private final Logger logger = (Logger) LoggerFactory.getLogger(GenericMessageListener.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Level previousLevel;
    private GenericMessageListenerTest.SampleListener listener;
    private ReceiverRecord<String, byte[]> failed;

    @BeforeEach
    void setUp() {
        previousLevel = logger.getLevel();
        logged.start();
        logger.addAppender(logged);
        listener = new GenericMessageListenerTest.SampleListener(mock(ReactiveMessageListener.class), false, false,
                0, 0, mock(DiscardNotifier.class), "event", mock(CustomReporter.class), "group",
                List.of("orders"), message -> Mono.empty());
        RecordHeaders headers = new RecordHeaders();
        headers.add("authorization", HEADER_VALUE.getBytes(StandardCharsets.UTF_8));
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<String, byte[]> consumerRecord = new ConsumerRecord<>("orders", 3, 42L,
                ConsumerRecord.NO_TIMESTAMP, TimestampType.NO_TIMESTAMP_TYPE, 0, body.length, "order-1\r\nforged",
                body, headers, Optional.empty());
        failed = new ReceiverRecord<>(consumerRecord, mock(ReceiverOffset.class));
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logged);
        logger.setLevel(previousLevel);
        listener.stop();
    }

    @Test
    void atWarnTheBodyAndTheHeaderValuesAreNotLogged() {
        logger.setLevel(Level.WARN);

        listener.logError(new IllegalStateException("boom"), failed, FallbackStrategy.DEFINITIVE_DISCARD);

        assertThat(logged.list).hasSize(1);
        ILoggingEvent line = logged.list.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getThrowableProxy()).as("no stack trace at WARN").isNull();
        assertThat(line.getFormattedMessage())
                .contains("orders", "partition 3", "offset 42", BODY.length() + " bytes",
                        IllegalStateException.class.getName())
                .doesNotContain(BODY, "SECRET", HEADER_VALUE, "header-secret")
                .as("a line break in the key cannot forge a log line")
                .doesNotContain("\n", "\r");
    }

    @Test
    void atDebugTheBodyTheHeadersAndTheStackTraceAreLogged() {
        logger.setLevel(Level.DEBUG);

        listener.logError(new IllegalStateException("boom"), failed, FallbackStrategy.DEFINITIVE_DISCARD);

        assertThat(logged.list).filteredOn(event -> event.getLevel() == Level.DEBUG)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage()).contains(BODY, "authorization=" + HEADER_VALUE);
                    assertThat(event.getThrowableProxy()).isNotNull();
                });
    }
}
