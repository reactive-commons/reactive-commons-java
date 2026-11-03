package org.reactivecommons.async.rabbit.listeners;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import com.sun.management.ThreadMXBean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.FallbackStrategy;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.Receiver;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A message that keeps failing is logged again on every retry: a retry is logged as one {@code WARN} line, without
 * stack trace, headers or body (those are logged at {@code DEBUG}), while the definitive discard is logged in full.
 */
class GenericMessageListenerErrorLogTest {

    private static final String BODY_MARKER = "BODY-MARKER";
    private static final String HEADER_VALUE = "HEADER-VALUE";
    private static final int MESSAGES = 2_000;

    private final Logger logger = (Logger) LoggerFactory.getLogger(GenericMessageListener.class);
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
    private final String testThread = Thread.currentThread().getName();
    private final AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
            if (event.getThreadName().equals(testThread)) { // not the listeners of the tests running meanwhile
                events.add(event);
            }
        }
    };
    private final TestListener listener = new TestListener();
    private final IllegalStateException failure = new IllegalStateException("the handler failed");
    private Level previousLevel;
    private boolean previousAdditivity;

    @BeforeEach
    void captureLogs() {
        previousLevel = logger.getLevel();
        previousAdditivity = logger.isAdditive();
        logger.setAdditive(false); // nothing reaches the console
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void stopCapturing() {
        logger.detachAppender(capture);
        logger.setAdditive(previousAdditivity);
        logger.setLevel(previousLevel);
    }

    @ParameterizedTest
    @EnumSource(value = FallbackStrategy.class, names = {"FAST_RETRY", "RETRY_DLQ"})
    void aRetryIsLoggedAsOneWarningWithoutStackTraceHeadersOrBody(FallbackStrategy strategy) {
        logger.setLevel(Level.INFO);

        listener.logError(failure, delivery(), strategy);

        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getThrowableProxy()).as("stack trace").isNull();
        assertThat(event.getFormattedMessage())
                .contains(String.format(strategy.message, "message-1"), "queue test-queue", "dead-lettered 2 times",
                        "redelivered true", "java.lang.IllegalStateException: the handler failed")
                .doesNotContain(BODY_MARKER, HEADER_VALUE);
    }

    @Test
    void withDebugEnabledARetryAlsoLogsItsStackTraceHeadersAndBody() {
        logger.setLevel(Level.DEBUG);

        listener.logError(failure, delivery(), FallbackStrategy.FAST_RETRY);

        assertThat(events).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.DEBUG);
        ILoggingEvent detail = events.get(1);
        assertThat(detail.getThrowableProxy()).as("stack trace").isNotNull();
        assertThat(detail.getFormattedMessage()).contains("message-1", "test-queue", BODY_MARKER, HEADER_VALUE);
    }

    @Test
    void theDefinitiveDiscardIsLoggedWithItsStackTraceHeadersAndBody() {
        logger.setLevel(Level.INFO);

        listener.logError(failure, delivery(), FallbackStrategy.DEFINITIVE_DISCARD);

        assertThat(events).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR, Level.WARN, Level.WARN);
        assertThat(events.get(0).getThrowableProxy()).as("stack trace").isNotNull();
        assertThat(events.get(1).getFormattedMessage()).contains(BODY_MARKER, HEADER_VALUE);
        assertThat(events.get(2).getFormattedMessage())
                .isEqualTo(String.format(FallbackStrategy.DEFINITIVE_DISCARD.message, "message-1"));
    }

    /**
     * Measured on the thread that logs, with an appender that renders each event as a text appender writes it.
     */
    @Test
    void aRetryCostsAFractionOfALogInFull() {
        logger.setLevel(Level.INFO);
        logger.detachAppender(capture); // measures only the rendering appender
        StringBuilder written = new StringBuilder();
        AppenderBase<ILoggingEvent> rendering = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                written.setLength(0);
                written.append(event.getFormattedMessage());
                if (event.getThrowableProxy() != null) {
                    written.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
                }
            }
        };
        rendering.start();
        logger.addAppender(rendering);
        try {
            AcknowledgableDelivery delivery = delivery();
            long retry = bytesPerLog(delivery, FallbackStrategy.FAST_RETRY);
            long discard = bytesPerLog(delivery, FallbackStrategy.DEFINITIVE_DISCARD);

            System.out.printf("failed message logged: retry %d bytes allocated, in full %d bytes allocated%n", retry,
                    discard);
            assertThat(retry).as("bytes allocated to log a retry").isLessThan(discard / 4);
        } finally {
            logger.detachAppender(rendering);
        }
    }

    private long bytesPerLog(AcknowledgableDelivery delivery, FallbackStrategy strategy) {
        for (int i = 0; i < MESSAGES; i++) { // warm-up
            listener.logError(failure, delivery, strategy);
        }
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < MESSAGES; i++) {
            listener.logError(failure, delivery, strategy);
        }
        return (threads.getCurrentThreadAllocatedBytes() - before) / MESSAGES;
    }

    /**
     * A message dead-lettered twice and redelivered, with a header and a body that a retry must not log.
     */
    private static AcknowledgableDelivery delivery() {
        Map<String, Object> death = new HashMap<>();
        death.put("count", 2L);
        death.put("queue", "test-queue");
        Map<String, Object> headers = new HashMap<>();
        headers.put("x-death", new ArrayList<>(List.of(death)));
        headers.put("custom-header", HEADER_VALUE);
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .messageId("message-1")
                .headers(headers)
                .build();
        byte[] body = ("{\"data\":\"" + BODY_MARKER + "\"}").getBytes(StandardCharsets.UTF_8);
        Delivery delivery = new Delivery(new Envelope(1L, true, "exchange", "rk"), properties, body);
        return new AcknowledgableDelivery(delivery, mock(Channel.class), (context, error) -> {
            throw new IllegalStateException(error);
        });
    }

    private static class TestListener extends GenericMessageListener {

        TestListener() {
            super(new ListenerContext<>(new ReactiveMessageListener(mock(Receiver.class), mock(TopologyCreator.class),
                    250, 250, new SubscriberRegistry(), new SimpleMeterRegistry(), "app", null), null, null,
                    new RetryPolicy(false, -1, 10), false, mock(DiscardNotifier.class), mock(CustomReporter.class)),
                    "test-queue", "event");
        }

        @Override
        protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
            return message -> Mono.empty();
        }

        @Override
        protected String getExecutorPath(AcknowledgableDelivery msj) {
            return msj.getEnvelope().getRoutingKey();
        }

        @Override
        protected Object parseMessageForReporter(Message msj) {
            return new Object();
        }

        @Override
        protected String getKind() {
            return "test";
        }
    }
}
