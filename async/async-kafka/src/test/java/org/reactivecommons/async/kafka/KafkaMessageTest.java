package org.reactivecommons.async.kafka;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.communications.Message;
import reactor.kafka.receiver.ReceiverRecord;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaMessageTest {

    @Mock
    private ReceiverRecord<String, byte[]> receiverRecord;

    @Test
    void shouldParse() {
        // Arrange
        RecordHeaders headers = new RecordHeaders();
        headers.add("content-type", "application/json".getBytes());
        when(receiverRecord.value()).thenReturn("value".getBytes());
        when(receiverRecord.key()).thenReturn("key");
        when(receiverRecord.topic()).thenReturn("topic");
        when(receiverRecord.headers()).thenReturn(headers);
        // Act
        Message message = KafkaMessage.fromDelivery(receiverRecord);
        // Assert
        assertEquals("key", message.getProperties().getKey());
        assertEquals("topic", message.getProperties().getTopic());
        assertEquals("application/json", message.getProperties().getContentType());
        assertEquals(5, message.getProperties().getContentLength());
        assertEquals("value", new String(message.getBody()));
    }

    @Test
    void shouldParseWhenNoContentType() {
        // Arrange
        RecordHeaders headers = new RecordHeaders();
        when(receiverRecord.value()).thenReturn("value".getBytes());
        when(receiverRecord.key()).thenReturn("key");
        when(receiverRecord.topic()).thenReturn("topic");
        when(receiverRecord.headers()).thenReturn(headers);
        // Act
        Message message = KafkaMessage.fromDelivery(receiverRecord);
        // Assert
        assertEquals("key", message.getProperties().getKey());
        assertEquals("topic", message.getProperties().getTopic());
        assertNull(message.getProperties().getContentType());
        assertEquals(5, message.getProperties().getContentLength());
        assertEquals("value", new String(message.getBody()));
    }

    @Test
    void shouldReadATombstoneAsAMessageWithoutBody() {
        // Arrange: the record of a compacted topic that deletes its key has no value
        when(receiverRecord.value()).thenReturn(null);
        when(receiverRecord.key()).thenReturn("key");
        when(receiverRecord.topic()).thenReturn("topic");
        when(receiverRecord.headers()).thenReturn(new RecordHeaders());
        // Act
        Message message = KafkaMessage.fromDelivery(receiverRecord);
        // Assert
        assertNull(message.getBody());
        assertEquals(0, message.getProperties().getContentLength());
        assertEquals("key", message.getProperties().getKey());
    }

    @Test
    void shouldKeepAHeaderWithoutValue() {
        // Arrange
        RecordHeaders headers = new RecordHeaders();
        headers.add("trace-id", null);
        when(receiverRecord.value()).thenReturn("value".getBytes(StandardCharsets.UTF_8));
        when(receiverRecord.headers()).thenReturn(headers);
        // Act
        Message message = KafkaMessage.fromDelivery(receiverRecord);
        // Assert
        assertThat(message.getProperties().getHeaders()).containsEntry("trace-id", null);
    }

    /**
     * The sender writes the header values as UTF-8, so they are read back as they were sent.
     */
    @Test
    void shouldReadTheHeadersWrittenBySenderAsTheyWereSent() {
        // Arrange
        String nonAscii = "ñandú, crème brûlée, 東京, €";
        RecordHeaders headers = new RecordHeaders();
        headers.add("note", nonAscii.getBytes(StandardCharsets.UTF_8));
        headers.add("empty", null);
        headers.add("content-type", "application/json".getBytes(StandardCharsets.UTF_8));
        when(receiverRecord.value()).thenReturn("value".getBytes(StandardCharsets.UTF_8));
        when(receiverRecord.headers()).thenReturn(headers);
        // Act
        Message message = KafkaMessage.fromDelivery(receiverRecord);
        // Assert
        assertThat(message.getProperties().getHeaders())
                .containsEntry("note", nonAscii)
                .containsEntry("empty", null)
                .containsEntry("content-type", "application/json");
    }
}
