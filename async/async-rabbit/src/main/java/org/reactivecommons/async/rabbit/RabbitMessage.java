package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Delivery;
import lombok.Data;
import org.reactivecommons.async.commons.communications.Message;

import java.util.HashMap;
import java.util.Map;

@Data
public class RabbitMessage implements Message {
    private final byte[] body;
    private final Properties properties;
    private final String type;

    @Data
    public static class RabbitMessageProperties implements Properties {
        private String contentType;
        private String contentEncoding;
        private long timestamp;
        private long contentLength;
        private Map<String, Object> headers = new HashMap<>();
    }

    public static RabbitMessage fromDelivery(Delivery delivery) {
        return fromDelivery(delivery, "");
    }

    public static RabbitMessage fromDelivery(Delivery delivery, String executorPath) {
        return new RabbitMessage(delivery.getBody(), createMessageProps(delivery), executorPath);
    }

    private static Message.Properties createMessageProps(Delivery msj) {
        final RabbitMessage.RabbitMessageProperties properties = new RabbitMessage.RabbitMessageProperties();
        if (msj.getProperties().getTimestamp() != null) {
            properties.setTimestamp(msj.getProperties().getTimestamp().getTime());
        }
        properties.setHeaders(msj.getProperties().getHeaders());
        properties.setContentType(msj.getProperties().getContentType());
        properties.setContentEncoding(msj.getProperties().getContentEncoding());
        return properties;
    }
}
