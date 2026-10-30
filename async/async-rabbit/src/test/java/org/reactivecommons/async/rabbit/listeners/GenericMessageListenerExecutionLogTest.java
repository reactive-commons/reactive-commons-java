package org.reactivecommons.async.rabbit.listeners;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.sun.management.ThreadMXBean;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.DiscardNotifier;
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
import java.lang.reflect.Method;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Every handled message logs its execution at {@code DEBUG}, a level usually disabled: building that line for
 * nothing costs CPU and garbage on every message. It is measured on the thread that logs, since the line is not
 * observable when its level is disabled.
 */
class GenericMessageListenerExecutionLogTest {

    private static final int MESSAGES = 10_000;

    @Test
    void theExecutionOfAMessageIsNotFormattedWhileItsLogLevelIsDisabled() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GenericMessageListener.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            TestListener listener = new TestListener();
            Method logExecution = GenericMessageListener.class.getDeclaredMethod("doLogExecution", String.class,
                    long.class);
            logExecution.setAccessible(true);
            Object[] arguments = {"app.event.name", 1_234L};
            for (int i = 0; i < MESSAGES; i++) { // warms up the reflective call, which allocates the first times
                logExecution.invoke(listener, arguments);
            }

            ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            long before = threads.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < MESSAGES; i++) {
                logExecution.invoke(listener, arguments);
            }
            long bytesPerMessage = (threads.getCurrentThreadAllocatedBytes() - before) / MESSAGES;

            System.out.printf("execution of a message logged at DEBUG while disabled: %d bytes allocated per "
                    + "message%n", bytesPerMessage);
            assertThat(bytesPerMessage).as("bytes allocated per message to log its execution at a disabled level")
                    .isLessThan(16);
        } finally {
            logger.setLevel(previous);
        }
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
