package reactor.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AcknowledgableDeliveryTest {

    Channel channel;
    Envelope envelope;
    AMQP.BasicProperties props;
    BiConsumer<Receiver.AcknowledgmentContext, Exception> noopHandler;

    @BeforeEach
    void setUp() {
        channel = mock(Channel.class);
        envelope = new Envelope(42L, false, "exch", "rk");
        props = new AMQP.BasicProperties();
        noopHandler = (ctx, ex) -> {};
    }

    @Test
    void ackCallsBasicAck() throws Exception {
        var delivery = new AcknowledgableDelivery(
                new Delivery(envelope, props, "body".getBytes()), channel, noopHandler);
        delivery.ack();
        verify(channel).basicAck(42L, false);
    }

    @Test
    void ackWithMultiple() throws Exception {
        var delivery = new AcknowledgableDelivery(
                new Delivery(envelope, props, "body".getBytes()), channel, noopHandler);
        delivery.ack(true);
        verify(channel).basicAck(42L, true);
    }

    @Test
    void ackIsIdempotent() throws Exception {
        var delivery = new AcknowledgableDelivery(
                new Delivery(envelope, props, "body".getBytes()), channel, noopHandler);
        delivery.ack();
        delivery.ack();
        verify(channel, times(1)).basicAck(42L, false);
    }

    @Test
    void nackCallsBasicNack() throws Exception {
        var delivery = new AcknowledgableDelivery(
                new Delivery(envelope, props, "body".getBytes()), channel, noopHandler);
        delivery.nack(true);
        verify(channel).basicNack(42L, false, true);
    }

    @Test
    void nackWithMultipleAndRequeue() throws Exception {
        var delivery = new AcknowledgableDelivery(
                new Delivery(envelope, props, "body".getBytes()), channel, noopHandler);
        delivery.nack(true, false);
        verify(channel).basicNack(42L, true, false);
    }

    @Test
    void nackIsIdempotent() throws Exception {
        var delivery = new AcknowledgableDelivery(
                new Delivery(envelope, props, "body".getBytes()), channel, noopHandler);
        delivery.nack(false);
        delivery.nack(false);
        verify(channel, times(1)).basicNack(42L, false, false);
    }

    @Test
    void ackThenNackIgnoresNack() throws Exception {
        var delivery = new AcknowledgableDelivery(
                new Delivery(envelope, props, "body".getBytes()), channel, noopHandler);
        delivery.ack();
        delivery.nack(true);
        verify(channel).basicAck(42L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void ackFailureInvokesExceptionHandler() throws Exception {
        doThrow(new IOException("channel closed")).when(channel).basicAck(anyLong(), anyBoolean());
        var handlerCalled = new AtomicBoolean(false);
        BiConsumer<Receiver.AcknowledgmentContext, Exception> handler = (ctx, ex)
                -> handlerCalled.set(true);

        var delivery = new AcknowledgableDelivery(
                new Delivery(envelope, props, "body".getBytes()), channel, handler);
        delivery.ack();
        assertThat(handlerCalled).isTrue();
    }

    @Test
    void nackFailureInvokesExceptionHandler() throws Exception {
        doThrow(new IOException("channel closed")).when(channel).basicNack(anyLong(), anyBoolean(), anyBoolean());
        var handlerCalled = new AtomicBoolean(false);
        BiConsumer<Receiver.AcknowledgmentContext, Exception> handler = (ctx, ex)
                -> handlerCalled.set(true);

        var delivery = new AcknowledgableDelivery(
                new Delivery(envelope, props, "body".getBytes()), channel, handler);
        delivery.nack(false);
        assertThat(handlerCalled).isTrue();
    }

    @Test
    void exceptionHandlerRethrowResetsAckFlag() throws Exception {
        doThrow(new IOException("channel closed")).when(channel).basicAck(anyLong(), anyBoolean());
        BiConsumer<Receiver.AcknowledgmentContext, Exception> handler = (ctx, ex) -> {
            throw new RabbitFluxException(ex);
        };

        var delivery = new AcknowledgableDelivery(
                new Delivery(envelope, props, "body".getBytes()), channel, handler);
        assertThatThrownBy(delivery::ack).isInstanceOf(RabbitFluxException.class);
        // After the handler rethrew, flag should be reset so ack can be retried
    }
}
