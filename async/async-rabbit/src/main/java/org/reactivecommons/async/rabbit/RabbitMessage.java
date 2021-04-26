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

    @Data
    public static class RabbitMessageProperties implements Properties{
        private String contentType;
        private String contentEncoding;
        private long contentLength;
        private Map<String, Object> headers = new HashMap<>();
    }

    public static RabbitMessage fromDelivery(Delivery delivery){
        return new RabbitMessage(delivery.getBody(), createMessageProps(delivery));
    }

    private static Message.Properties createMessageProps(Delivery msj) {
        final RabbitMessage.RabbitMessageProperties properties = new RabbitMessage.RabbitMessageProperties();
        properties.setHeaders(msj.getProperties().getHeaders());
        properties.setContentType(msj.getProperties().getContentType());
        properties.setContentEncoding(msj.getProperties().getContentEncoding());
        return properties;
    }
}
