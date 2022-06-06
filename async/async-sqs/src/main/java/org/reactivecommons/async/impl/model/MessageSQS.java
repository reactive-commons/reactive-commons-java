package org.reactivecommons.async.impl.model;

import lombok.Data;

@Data
public class MessageSQS implements org.reactivecommons.async.impl.communications.Message {
    private byte[] body;
    private Properties properties;
    public MessageSQS(String message) {
        body = message.getBytes();
    }
}