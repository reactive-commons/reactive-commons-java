package org.reactivecommons.async.kafka;

import lombok.Data;
import org.apache.kafka.common.header.Headers;
import org.reactivecommons.async.commons.communications.Message;
import reactor.kafka.receiver.ReceiverRecord;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.reactivecommons.async.kafka.converters.json.KafkaJacksonMessageConverter.CONTENT_TYPE;


@Data
public class KafkaMessage implements Message {
    private final byte[] body;
    private final Properties properties;
    private final String type;

    @Data
    public static class KafkaMessageProperties implements Properties {
        private long contentLength;
        private String key;
        private String topic;
        private Map<String, Object> headers = new HashMap<>();

        @Override
        public String getContentType() {
            return (String) headers.get(CONTENT_TYPE);
        }
    }

    public static KafkaMessage fromDelivery(ReceiverRecord<String, byte[]> receiverRecord) {
        return fromDelivery(receiverRecord, null);
    }

    public static KafkaMessage fromDelivery(ReceiverRecord<String, byte[]> receiverRecord, String type) {
        return new KafkaMessage(receiverRecord.value(), createMessageProps(receiverRecord), type);
    }

    /**
     * A record without a value (a tombstone of a compacted topic) has a {@code null} body and a content length of 0.
     */
    private static Properties createMessageProps(ReceiverRecord<String, byte[]> receiverRecord) {
        Map<String, Object> headers = parseHeaders(receiverRecord.headers());

        final KafkaMessageProperties properties = new KafkaMessageProperties();
        properties.setHeaders(headers);
        properties.setKey(receiverRecord.key());
        properties.setTopic(receiverRecord.topic());
        byte[] value = receiverRecord.value();
        properties.setContentLength(value == null ? 0 : value.length);
        return properties;
    }

    /**
     * Reads the header values as UTF-8, the charset the sender writes them with, whatever the default charset of the
     * JVM is. Kafka allows a header without a value: it is kept, with a {@code null} value.
     */
    private static Map<String, Object> parseHeaders(Headers headers) {
        Map<String, Object> parsedHeaders = new HashMap<>();
        headers.forEach(header -> parsedHeaders.put(header.key(),
                header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8)));
        return parsedHeaders;
    }
}
