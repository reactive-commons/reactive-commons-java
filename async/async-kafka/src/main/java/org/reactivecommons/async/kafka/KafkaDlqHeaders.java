package org.reactivecommons.async.kafka;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Headers added to a message sent to a DLQ: where it was read from and the failure that discarded it, so it can be
 * diagnosed, or published again, without searching the logs. Their values are UTF-8 text.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KafkaDlqHeaders {
    public static final String ORIGINAL_TOPIC = "x-dlq-original-topic";
    public static final String ORIGINAL_PARTITION = "x-dlq-original-partition";
    public static final String ORIGINAL_OFFSET = "x-dlq-original-offset";
    public static final String EXCEPTION_CLASS = "x-dlq-exception-class";
    /**
     * Absent when the failure has no message.
     */
    public static final String EXCEPTION_MESSAGE = "x-dlq-exception-message";

    /**
     * A failure may carry a long message (e.g. a whole response body): only its beginning is kept, so the record of
     * the DLQ does not grow past what the broker accepts.
     */
    static final int MAX_EXCEPTION_MESSAGE_LENGTH = 1000;

    /**
     * Key of the subscriber context through which a listener hands these headers to the event bus that publishes its
     * discards: the discard notifier publishes a new event built from the message, and has no parameter for them.
     */
    private static final String CONTEXT_KEY = KafkaDlqHeaders.class.getName();

    /**
     * The headers of a record discarded because of the given failure.
     */
    public static Map<String, Object> of(ReceiverRecord<String, byte[]> discarded, Throwable failure) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(ORIGINAL_TOPIC, discarded.topic());
        headers.put(ORIGINAL_PARTITION, String.valueOf(discarded.partition()));
        headers.put(ORIGINAL_OFFSET, String.valueOf(discarded.offset()));
        headers.put(EXCEPTION_CLASS, failure.getClass().getName());
        String message = failure.getMessage();
        if (message != null) {
            headers.put(EXCEPTION_MESSAGE, message.length() > MAX_EXCEPTION_MESSAGE_LENGTH
                    ? message.substring(0, MAX_EXCEPTION_MESSAGE_LENGTH)
                    : message);
        }
        return headers;
    }

    /**
     * Puts the headers in a subscriber context, where {@link #from(ContextView)} reads them.
     */
    public static Context into(Context context, Map<String, Object> headers) {
        return context.put(CONTEXT_KEY, headers);
    }

    /**
     * The headers put in the subscriber context, or an empty map.
     */
    public static Map<String, Object> from(ContextView context) {
        return context.getOrDefault(CONTEXT_KEY, Map.of());
    }
}
