package reactor.rabbitmq;

import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboundMessageTest {

    @Test
    void constructorWithoutProperties() {
        var msg = new OutboundMessage("exch", "rk", "body".getBytes());
        assertThat(msg.getExchange()).isEqualTo("exch");
        assertThat(msg.getRoutingKey()).isEqualTo("rk");
        assertThat(msg.getProperties()).isNull();
        assertThat(msg.getBody()).isEqualTo("body".getBytes());
        assertThat(msg.isPublished()).isFalse();
    }

    @Test
    void constructorWithProperties() {
        var props = new AMQP.BasicProperties.Builder().contentType("text/plain").build();
        var msg = new OutboundMessage("exch", "rk", props, "body".getBytes());
        assertThat(msg.getExchange()).isEqualTo("exch");
        assertThat(msg.getRoutingKey()).isEqualTo("rk");
        assertThat(msg.getProperties()).isSameAs(props);
        assertThat(msg.getBody()).isEqualTo("body".getBytes());
    }

    @Test
    void publishedFlagIsSetByPublished() {
        var msg = new OutboundMessage("exch", "rk", new byte[0]);
        assertThat(msg.isPublished()).isFalse();
        msg.published();
        assertThat(msg.isPublished()).isTrue();
    }

    @Test
    void toStringContainsFieldValues() {
        var msg = new OutboundMessage("exch", "rk", "hi".getBytes());
        var str = msg.toString();
        assertThat(str).contains("exch").contains("rk");
    }

    @Test
    void shouldCreateMessageWithAckNotifier() {
        AMQP.BasicProperties properties = new AMQP.BasicProperties();
        byte[] body = "test message".getBytes();
        AtomicBoolean ackCalled = new AtomicBoolean(false);

        OutboundMessage message = new OutboundMessage(
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

        OutboundMessage message = new OutboundMessage(
                "test.exchange", "test.routingKey", new AMQP.BasicProperties(),
                "test message".getBytes(), ackCalled::set
        );

        message.getAckNotifier().accept(true);

        assertTrue(ackCalled.get());
    }

    @Test
    void shouldCreateMessageWithoutAckNotifier() {
        OutboundMessage message = new OutboundMessage(
                "test.exchange", "test.routingKey", new AMQP.BasicProperties(), "body".getBytes()
        );
        assertNull(message.getAckNotifier());
    }
}
