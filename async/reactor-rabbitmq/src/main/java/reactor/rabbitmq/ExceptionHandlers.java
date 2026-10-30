/*
 * Copyright (c) 2018-2021 VMware Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.rabbitmq;

import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ExceptionHandlers {

    public static final Predicate<Throwable> CONNECTION_RECOVERY_PREDICATE = new ConnectionRecoveryTriggeringPredicate();

    private static final Logger LOGGER = LoggerFactory.getLogger(ExceptionHandlers.class);

    public static class ConnectionRecoveryTriggeringPredicate implements Predicate<Throwable> {
        @Override
        public boolean test(Throwable throwable) {
            if (throwable instanceof ShutdownSignalException sse) {
                return AutorecoveringConnection.DEFAULT_CONNECTION_RECOVERY_TRIGGERING_CONDITION.test(sse);
            }
            return false;
        }
    }

    public static class SimpleRetryTemplate {

        private final long timeout;
        private final long waitingTime;
        private final Predicate<Throwable> predicate;
        private final boolean failOnTimeout;

        public SimpleRetryTemplate(Duration timeout, Duration waitingTime, Predicate<Throwable> predicate) {
            this(timeout, waitingTime, predicate, true);
        }

        public SimpleRetryTemplate(Duration timeout, Duration waitingTime, Predicate<Throwable> predicate,
                boolean failOnTimeout) {
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("Timeout must be greater than 0");
            }
            if (waitingTime == null || waitingTime.isNegative() || waitingTime.isZero()) {
                throw new IllegalArgumentException("Waiting time must be greater than 0");
            }
            if (timeout.compareTo(waitingTime) <= 0) {
                throw new IllegalArgumentException("Timeout must be greater than waiting time");
            }
            if (predicate == null) {
                throw new NullPointerException("Predicate cannot be null");
            }
            this.timeout = timeout.toMillis();
            this.waitingTime = waitingTime.toMillis();
            this.predicate = predicate;
            this.failOnTimeout = failOnTimeout;
        }

        public void retry(Callable<Void> operation, Exception e) {
            if (predicate.test(e)) {
                long elapsedTime = 0;
                boolean callSucceeded = false;
                while (elapsedTime < timeout) {
                    try {
                        Thread.sleep(waitingTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RabbitFluxException("Thread interrupted while retry on sending", ie);
                    }
                    elapsedTime += waitingTime;
                    try {
                        operation.call();
                        callSucceeded = true;
                        break;
                    } catch (Exception sendingException) {
                        if (!predicate.test(sendingException)) {
                            throw new RabbitFluxException("Not retryable exception thrown during retry",
                                    sendingException);
                        }
                    }
                }
                if (!callSucceeded && failOnTimeout) {
                    throw new RabbitFluxRetryTimeoutException("Retry timed out after " + this.timeout + " ms", e);
                }
            } else {
                throw new RabbitFluxException("Not retryable exception, cannot retry", e);
            }
        }
    }

    /**
     * The default handler of the acknowledgments (see {@link ConsumeOptions}), which never blocks the thread that
     * acknowledges:
     * <ul>
     *     <li>The channel of the delivery is closed (e.g. its connection is being recovered): the acknowledgment is
     *     skipped with a warning. The broker redelivers the message, and retrying the acknowledgment on the recovered
     *     channel would not send it either: its delivery tag belongs to the closed channel.</li>
     *     <li>Any other failure: a {@link RabbitFluxException}.</li>
     * </ul>
     */
    public static class FailFastAcknowledgmentExceptionHandler
            implements BiConsumer<Receiver.AcknowledgmentContext, Exception> {

        @Override
        public void accept(Receiver.AcknowledgmentContext acknowledgmentContext, Exception e) {
            if (isChannelClosed(e)) {
                LOGGER.warn("Acknowledgment not sent, the channel is closed (the broker redelivers the message): {}",
                        e.getMessage());
                return;
            }
            throw new RabbitFluxException("Unable to acknowledge the message", e);
        }

        private static boolean isChannelClosed(Throwable error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ShutdownSignalException) {
                    return true;
                }
                if (cause.getCause() == cause) {
                    break;
                }
            }
            return false;
        }
    }

    /**
     * The default handler of the publications (see {@link SendOptions}), which never blocks the thread that publishes:
     * a publication on a connection being recovered fails right away with a {@link RabbitFluxRetryTimeoutException} (a
     * publisher-confirms flow reports the message as not published and goes on), any other failure with a
     * {@link RabbitFluxException}. To publish again once the connection is recovered, retry the publication (e.g. with
     * {@code retryWhen}), or use a {@link RetrySendingExceptionHandler}, which blocks the thread while it retries.
     */
    public static class FailFastSendingExceptionHandler implements BiConsumer<Sender.SendContext, Exception> {

        @Override
        public void accept(Sender.SendContext sendContext, Exception e) {
            if (CONNECTION_RECOVERY_PREDICATE.test(e)) {
                throw new RabbitFluxRetryTimeoutException("Connection is being recovered, message not published", e);
            }
            throw new RabbitFluxException("Unable to publish the message", e);
        }
    }

    /**
     * Retries the acknowledgment while the connection is being recovered, sleeping on the thread that acknowledges, and
     * gives up silently once the timeout elapses. An acknowledgment retried on the recovered channel is not sent: its
     * delivery tag belongs to the closed channel, and the broker redelivers the message.
     */
    public static class RetryAcknowledgmentExceptionHandler
            implements BiConsumer<Receiver.AcknowledgmentContext, Exception> {

        private final SimpleRetryTemplate retryTemplate;

        public RetryAcknowledgmentExceptionHandler(Duration timeout, Duration waitingTime,
                Predicate<Throwable> predicate) {
            this.retryTemplate = new SimpleRetryTemplate(timeout, waitingTime, predicate, false);
        }

        @Override
        public void accept(Receiver.AcknowledgmentContext acknowledgmentContext, Exception e) {
            retryTemplate.retry(() -> {
                acknowledgmentContext.ackOrNack();
                return null;
            }, e);
        }
    }

    /**
     * Retries the publication while the connection is being recovered, sleeping on the thread that publishes.
     */
    public static class RetrySendingExceptionHandler implements BiConsumer<Sender.SendContext, Exception> {

        private final SimpleRetryTemplate retryTemplate;

        public RetrySendingExceptionHandler(Duration timeout, Duration waitingTime,
                Predicate<Throwable> predicate) {
            this(timeout, waitingTime, predicate, true);
        }

        public RetrySendingExceptionHandler(Duration timeout, Duration waitingTime,
                Predicate<Throwable> predicate, boolean failOnTimeout) {
            this.retryTemplate = new SimpleRetryTemplate(timeout, waitingTime, predicate, failOnTimeout);
        }

        @Override
        public void accept(Sender.SendContext sendContext, Exception e) {
            retryTemplate.retry(() -> {
                sendContext.publish();
                return null;
            }, e);
        }
    }
}
