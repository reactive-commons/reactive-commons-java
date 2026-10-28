package org.reactivecommons.async.rabbit.communications;

import org.jspecify.annotations.NonNull;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.commons.exceptions.SendFailureMessageTooLargeException;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import org.reactivecommons.async.commons.exceptions.SendFailureReturnedException;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;

import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The progress of a batch published with confirms ({@link ReactiveMessageSender#sendWithConfirmBatch}):
 * <ul>
 *     <li>each message taken from the batch must be confirmed within the send timeout, however long the whole batch
 *     takes;</li>
 *     <li>a failed message does not stop the batch: once every message is settled, the batch fails with the exception
 *     of its first failure, whose message tells which messages failed and why.</li>
 * </ul>
 */
final class ConfirmBatch {

    static final int MAX_LISTED_FAILURES = 100;

    private enum Failure {
        RETURNED("returned", SendFailureReturnedException::new),
        NACK("nack", SendFailureNoAckException::new),
        IN_DOUBT("in doubt", SendConfirmUnknownException::new),
        TOO_LARGE("too large", (message, cause) -> new SendFailureMessageTooLargeException(message));

        private final String reason;
        private final BiFunction<String, Throwable, RuntimeException> exception;

        Failure(String reason, BiFunction<String, Throwable, RuntimeException> exception) {
            this.reason = reason;
            this.exception = exception;
        }
    }

    private record FailedMessage(String messageId, Failure failure) {
        @Override
        public @NonNull String toString() {
            return messageId + " (" + failure.reason + ")";
        }
    }

    private final Duration sendTimeout;
    private final long startedAt = System.nanoTime();
    private volatile boolean sourceSubscribed;
    // OutboundMessage keeps the identity equality: the publication time of each message waiting for its confirmation
    private final Map<OutboundMessage, Long> unconfirmed = new ConcurrentHashMap<>();
    private final AtomicInteger taken = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final Queue<FailedMessage> failures = new ConcurrentLinkedQueue<>();

    ConfirmBatch(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    /**
     * How often {@link #expired()} is checked: often enough to fail close to the send timeout.
     */
    Duration checkPeriod() {
        return Duration.ofMillis(Math.max(10, Math.min(1_000, sendTimeout.toMillis() / 10)));
    }

    void sourceSubscribed() {
        sourceSubscribed = true;
    }

    OutboundMessage published(OutboundMessage message) {
        taken.incrementAndGet();
        unconfirmed.put(message, System.nanoTime());
        return message;
    }

    void tooLarge(String messageId) {
        taken.incrementAndGet();
        fail(messageId, Failure.TOO_LARGE);
    }

    void settled(OutboundMessageResult<OutboundMessage> result) {
        final OutboundMessage message = result.outboundMessage();
        if (message != null) {
            unconfirmed.remove(message);
        }
        if (result.returned()) { // a returned message is also acked: it must be checked first
            fail(messageIdOf(message), Failure.RETURNED);
        } else if (result.unknown()) {
            fail(messageIdOf(message), Failure.IN_DOUBT);
        } else if (!result.ack()) {
            fail(messageIdOf(message), Failure.NACK);
        }
    }

    /**
     * The failure of the batch when a message was not confirmed within the send timeout, or when the batch could
     * not start taking messages within it (e.g. no channel could be opened); {@code null} otherwise.
     */
    RuntimeException expired() {
        final long timeoutNanos = sendTimeout.toNanos();
        final long now = System.nanoTime();
        if (!sourceSubscribed) {
            return now - startedAt > timeoutNanos
                    ? new SendConfirmUnknownException("No confirmation received within " + sendTimeout
                    + ": the batch could not start publishing")
                    : null;
        }
        if (unconfirmed.values().stream().noneMatch(publishedAt -> now - publishedAt > timeoutNanos)) {
            return null;
        }
        unconfirmed.keySet().forEach(message -> fail(messageIdOf(message), Failure.IN_DOUBT));
        return new SendConfirmUnknownException("No confirmation received within " + sendTimeout + ": "
                + summary() + "; the batch was cancelled, so the messages not taken from it yet were not published");
    }

    /**
     * The failure of a batch that stopped before taking all its messages (e.g. its channel was closed by the
     * broker): the messages that failed so far are reported along with the cause.
     */
    Throwable stoppedBy(Throwable cause) {
        if (failures.isEmpty()) {
            return cause;
        }
        return firstFailure().exception.apply(summary() + "; the batch stopped (" + cause.getMessage()
                + "), so the messages not taken from it yet were not published", cause);
    }

    /**
     * Completes when every message of the batch was confirmed, fails with the exception of the first failure
     * otherwise.
     */
    <T> Mono<T> outcome() {
        if (failures.isEmpty()) {
            return Mono.empty();
        }
        return Mono.error(firstFailure().exception.apply(summary(), null));
    }

    private void fail(String messageId, Failure failure) {
        if (failed.incrementAndGet() <= MAX_LISTED_FAILURES) {
            failures.add(new FailedMessage(messageId, failure));
        }
    }

    private Failure firstFailure() {
        return failures.element().failure();
    }

    private String summary() {
        final int count = failed.get();
        final String listed = failures.stream().map(FailedMessage::toString).collect(Collectors.joining(", "));
        return count + " of the " + taken.get() + " messages taken from the batch failed: " + listed
                + (count > MAX_LISTED_FAILURES ? " and " + (count - MAX_LISTED_FAILURES) + " more" : "");
    }

    private static String messageIdOf(OutboundMessage message) {
        return message == null || message.getProperties() == null ? null : message.getProperties().getMessageId();
    }
}
