package org.reactivecommons.async.rabbit.communications;

import io.micrometer.core.instrument.MeterRegistry;
import org.reactivecommons.async.commons.exceptions.SendFailureHandOverRejectedException;
import org.reactivecommons.async.commons.exceptions.SendFailureMessageTooLargeException;

import java.time.Duration;

/**
 * How a {@link ReactiveMessageSender} publishes the messages of a domain.
 *
 * @param mandatory             whether the messages are published as mandatory: the broker returns the ones it cannot
 *                              route to any queue
 * @param sendTimeout           how long a caller waits for the confirmation of its message
 * @param handOverPoolSize      the threads of both the pool that hands the messages to the publishing flows and the
 *                              pool that completes the callers; {@code null}: one hand-over thread per processor (2 to
 *                              13) and 13 threads to complete the callers
 * @param handOverQueueCapacity the messages that may wait to be published: once full, a publication fails with
 *                              {@link SendFailureHandOverRejectedException}
 * @param maxMessageSize        the {@code max_message_size} of the broker, in bytes: a message with a larger body is
 *                              rejected with {@link SendFailureMessageTooLargeException} before being written, instead
 *                              of making the broker close the channel it shares with other messages
 * @param blockedState          whether the broker blocks the connection of the sender; a new one when {@code null}
 * @param meterRegistry         where the publishing metrics are registered; none when {@code null}
 * @param domain                the domain the publishing metrics are tagged with
 */
public record SenderSettings(boolean mandatory, Duration sendTimeout, Integer handOverPoolSize,
                             int handOverQueueCapacity, int maxMessageSize, ConnectionBlockedState blockedState,
                             MeterRegistry meterRegistry, String domain) {
}
