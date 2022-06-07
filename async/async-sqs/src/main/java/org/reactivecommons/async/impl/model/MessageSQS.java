package org.reactivecommons.async.impl.model;

import lombok.Data;
import org.reactivecommons.async.commons.communications.Message;

import java.util.HashMap;
import java.util.Map;

@Data
public class MessageSQS implements Message {
    private byte[] body;
    private Properties properties;
    public MessageSQS(String message) {
        body = message.getBytes();
    }

    @Data
    public static class RabbitMessageProperties implements Properties{
        private String contentType;
        private String contentEncoding;
        private long contentLength;
        private Map<String, Object> headers = new HashMap<>();
    }
}