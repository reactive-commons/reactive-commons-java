package org.reactivecommons.async.kafka.listeners;

import java.time.Duration;

/**
 * What the listeners of a domain report about the messages of each topic. The methods are called on the threads of
 * the listeners, once per event, and must not block nor fail. Without metrics ({@link #NONE}) nothing is recorded.
 */
public interface KafkaListenerMetrics {
    /**
     * The message was handled and acknowledged.
     */
    String OUTCOME_ACK = "ack";
    /**
     * The handler failed for good and the message was logged and skipped, without a DLQ.
     */
    String OUTCOME_DISCARD = "discard";
    /**
     * The handler failed for good and the message was sent to its DLQ.
     */
    String OUTCOME_DLQ = "dlq";

    KafkaListenerMetrics NONE = new KafkaListenerMetrics() {
    };

    /**
     * A message is done with: handled, discarded or sent to its DLQ, and acknowledged.
     *
     * @param outcome {@link #OUTCOME_ACK}, {@link #OUTCOME_DISCARD} or {@link #OUTCOME_DLQ}
     * @param took    from the start of its handling to its outcome, retries included
     */
    default void messageDone(String topic, String outcome, Duration took) {
    }

    /**
     * An attempt of the handler of a message failed, whether it is retried or not.
     */
    default void handlerFailed(String topic) {
    }

    /**
     * A failed handler is run again on the same message.
     */
    default void handlerRetried(String topic) {
    }

    /**
     * The listener of the topic stopped consuming and was subscribed again.
     */
    default void listenerRestarted(String topic) {
    }
}
