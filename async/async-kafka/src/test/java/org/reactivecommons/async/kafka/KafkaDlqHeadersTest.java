package org.reactivecommons.async.kafka;

import org.junit.jupiter.api.Test;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.context.Context;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaDlqHeadersTest {

    @Test
    void shouldDescribeWhereTheRecordCameFromAndWhyItWasDiscarded() {
        ReceiverRecord<String, byte[]> discarded = discarded();

        Map<String, Object> headers = KafkaDlqHeaders.of(discarded, new IllegalStateException("dependency down"));

        assertThat(headers)
                .containsEntry(KafkaDlqHeaders.ORIGINAL_TOPIC, "orders")
                .containsEntry(KafkaDlqHeaders.ORIGINAL_PARTITION, "3")
                .containsEntry(KafkaDlqHeaders.ORIGINAL_OFFSET, "1234")
                .containsEntry(KafkaDlqHeaders.EXCEPTION_CLASS, "java.lang.IllegalStateException")
                .containsEntry(KafkaDlqHeaders.EXCEPTION_MESSAGE, "dependency down");
    }

    @Test
    void shouldKeepOnlyTheBeginningOfALongFailureMessage() {
        String longMessage = "x".repeat(KafkaDlqHeaders.MAX_EXCEPTION_MESSAGE_LENGTH + 500);

        Map<String, Object> headers = KafkaDlqHeaders.of(discarded(), new IllegalStateException(longMessage));

        assertThat((String) headers.get(KafkaDlqHeaders.EXCEPTION_MESSAGE))
                .hasSize(KafkaDlqHeaders.MAX_EXCEPTION_MESSAGE_LENGTH);
    }

    @Test
    void shouldLeaveOutTheMessageOfAFailureWithoutOne() {
        Map<String, Object> headers = KafkaDlqHeaders.of(discarded(), new NullPointerException());

        assertThat(headers).doesNotContainKey(KafkaDlqHeaders.EXCEPTION_MESSAGE)
                .containsEntry(KafkaDlqHeaders.EXCEPTION_CLASS, "java.lang.NullPointerException");
    }

    @Test
    void shouldReadTheHeadersPutInTheSubscriberContext() {
        Map<String, Object> headers = Map.of(KafkaDlqHeaders.ORIGINAL_TOPIC, "orders");

        assertThat(KafkaDlqHeaders.from(KafkaDlqHeaders.into(Context.empty(), headers))).isEqualTo(headers);
        assertThat(KafkaDlqHeaders.from(Context.empty())).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static ReceiverRecord<String, byte[]> discarded() {
        ReceiverRecord<String, byte[]> discarded = mock(ReceiverRecord.class);
        when(discarded.topic()).thenReturn("orders");
        when(discarded.partition()).thenReturn(3);
        when(discarded.offset()).thenReturn(1234L);
        return discarded;
    }
}
