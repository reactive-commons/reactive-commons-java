package reactor.rabbitmq;

import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
