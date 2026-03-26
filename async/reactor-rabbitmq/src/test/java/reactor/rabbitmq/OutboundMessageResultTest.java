package reactor.rabbitmq;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundMessageResultTest {

    @Test
    void twoArgConstructorDefaultsReturnedToFalse() {
        var msg = new OutboundMessage("exch", "rk", "body".getBytes());
        var result = new OutboundMessageResult<>(msg, true);
        assertThat(result.outboundMessage()).isSameAs(msg);
        assertThat(result.ack()).isTrue();
        assertThat(result.returned()).isFalse();
    }

    @Test
    void threeArgConstructor() {
        var msg = new OutboundMessage("exch", "rk", "body".getBytes());
        var result = new OutboundMessageResult<>(msg, false, true);
        assertThat(result.outboundMessage()).isSameAs(msg);
        assertThat(result.ack()).isFalse();
        assertThat(result.returned()).isTrue();
    }

    @Test
    void toStringContainsAckAndReturned() {
        var msg = new OutboundMessage("exch", "rk", "body".getBytes());
        var result = new OutboundMessageResult<>(msg, true, true);
        var str = result.toString();
        assertThat(str).contains("ack=true").contains("returned=true");
    }
}
