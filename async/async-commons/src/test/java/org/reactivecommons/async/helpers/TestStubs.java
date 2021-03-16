package org.reactivecommons.async.helpers;

import org.reactivecommons.async.impl.RabbitMessage;
import org.reactivecommons.async.parent.communications.Message;

import static org.reactivecommons.async.parent.Headers.*;

public class TestStubs {

    public static Message mockMessage() {
        Message.Properties properties = new RabbitMessage.RabbitMessageProperties();
        properties.getHeaders().put(REPLY_ID, "reply");
        properties.getHeaders().put(CORRELATION_ID, "correlation");
        properties.getHeaders().put(SERVED_QUERY_ID, "my-query");
        return new RabbitMessage("{\"id\":\"id\",\"name\":\"name\",\"date\":\"2020-10-22T17:03:26.062Z\"}".getBytes()
                , properties);
    }
}
