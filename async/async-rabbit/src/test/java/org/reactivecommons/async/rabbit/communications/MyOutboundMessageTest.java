package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyOutboundMessageTest {

    @Test
    void shouldCreateMessageWithCorrectProperties() {
        AMQP.BasicProperties properties = new AMQP.BasicProperties();
        byte[] body = "test message".getBytes();
        AtomicBoolean ackCalled = new AtomicBoolean(false);

        MyOutboundMessage message = new MyOutboundMessage(
                "test.exchange", "test.routingKey", properties, body, ackCalled::set
        );

        assertEquals("test.exchange", message.getExchange());
        assertEquals("test.routingKey", message.getRoutingKey());
        assertEquals(properties, message.getProperties());
        assertArrayEquals(body, message.getBody());
        assertNotNull(message.getAckNotifier());
    }

    @Test
    void shouldInvokeAckNotifierWhenCalled() {
        AtomicBoolean ackCalled = new AtomicBoolean(false);

        MyOutboundMessage message = new MyOutboundMessage(
                "test.exchange", "test.routingKey", new AMQP.BasicProperties(),
                "test message".getBytes(), ackCalled::set
        );

        message.getAckNotifier().accept(true);

        assertTrue(ackCalled.get());
    }
}