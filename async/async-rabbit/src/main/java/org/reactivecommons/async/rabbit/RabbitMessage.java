package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Delivery;
import lombok.Data;
import org.reactivecommons.async.commons.communications.Message;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Data
public class RabbitMessage implements Message {
    static final int MAX_LOGGED_BODY_BYTES = 1_024;

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

    /**
     * The beginning of a body, as text, for the logs: a body can be as large as the broker accepts (16 MiB by
     * default) and failed messages are logged again on every retry.
     *
     * @param body the body of a message
     * @return its first {@value #MAX_LOGGED_BODY_BYTES} bytes as UTF-8, with the total size when it is longer
     */
    public static String bodyPreview(byte[] body) {
        if (body.length <= MAX_LOGGED_BODY_BYTES) {
            return new String(body, StandardCharsets.UTF_8);
        }
        return new String(body, 0, MAX_LOGGED_BODY_BYTES, StandardCharsets.UTF_8)
                + "... (truncated, " + body.length + " bytes)";
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
