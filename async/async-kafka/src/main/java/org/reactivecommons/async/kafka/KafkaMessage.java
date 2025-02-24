package org.reactivecommons.async.kafka;

import lombok.Data;
import org.apache.kafka.common.header.Headers;
import org.reactivecommons.async.commons.communications.Message;
import reactor.kafka.receiver.ReceiverRecord;

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

    public static KafkaMessage fromDelivery(ReceiverRecord<String, byte[]> record) {
        return fromDelivery(record, null);
    }

    public static KafkaMessage fromDelivery(ReceiverRecord<String, byte[]> receiverRecord, String type) {
        return new KafkaMessage(receiverRecord.value(), createMessageProps(receiverRecord), type);
    }

    private static Properties createMessageProps(ReceiverRecord<String, byte[]> receiverRecord) {
        Map<String, Object> headers = parseHeaders(receiverRecord.headers());

        final KafkaMessageProperties properties = new KafkaMessageProperties();
        properties.setHeaders(headers);
        properties.setKey(receiverRecord.key());
        properties.setTopic(receiverRecord.topic());
        properties.setContentLength(receiverRecord.value().length);
        return properties;
    }

    private static Map<String, Object> parseHeaders(Headers headers) {
        Map<String, Object> parsedHeaders = new HashMap<>();
        headers.forEach(header -> parsedHeaders.put(header.key(), new String(header.value())));
        return parsedHeaders;
    }
}
