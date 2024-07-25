package org.reactivecommons.async.kafka;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.communications.Message;
import reactor.kafka.receiver.ReceiverRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaMessageTest {

    @Mock
    private ReceiverRecord<String, byte[]> record;

    @Test
    void shouldParse() {
        // Arrange
        RecordHeaders headers = new RecordHeaders();
        headers.add("content-type", "application/json".getBytes());
        when(record.value()).thenReturn("value".getBytes());
        when(record.key()).thenReturn("key");
        when(record.topic()).thenReturn("topic");
        when(record.headers()).thenReturn(headers);
        // Act
        Message message = KafkaMessage.fromDelivery(record);
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
        when(record.value()).thenReturn("value".getBytes());
        when(record.key()).thenReturn("key");
        when(record.topic()).thenReturn("topic");
        when(record.headers()).thenReturn(headers);
        // Act
        Message message = KafkaMessage.fromDelivery(record);
        // Assert
        assertEquals("key", message.getProperties().getKey());
        assertEquals("topic", message.getProperties().getTopic());
        assertNull(message.getProperties().getContentType());
        assertEquals(5, message.getProperties().getContentLength());
        assertEquals("value", new String(message.getBody()));
    }
}
