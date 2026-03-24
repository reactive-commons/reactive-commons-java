package reactor.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReceiverTest {

    Connection connection;
    Channel channel;
    Receiver receiver;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        channel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(channel);
        when(channel.isOpen()).thenReturn(true);
        when(channel.getConnection()).thenReturn(connection);
    }

    @AfterEach
    void tearDown() {
        if (receiver != null) {
            receiver.close();
        }
    }

    @Test
    void consumeManualAckRegistersConsumer() throws Exception {
        AtomicReference<DeliverCallback> deliverRef = new AtomicReference<>();
        when(channel.basicConsume(anyString(), eq(false), anyString(), eq(false), eq(false),
                anyMap(), any(DeliverCallback.class), any(com.rabbitmq.client.CancelCallback.class)))
                .thenAnswer(invocation -> {
                    deliverRef.set(invocation.getArgument(6));
                    return "consumer-tag-1";
                });

        receiver = new Receiver(new ReceiverOptions().connectionMono(Mono.just(connection)));

        var latch = new CountDownLatch(1);
        receiver.consumeManualAck("test-queue")
                .subscribe(delivery -> {
                    delivery.ack();
                    latch.countDown();
                });

        // Simulate delivery
        if (deliverRef.get() != null) {
            var envelope = new com.rabbitmq.client.Envelope(1L, false, "exch", "rk");
            var props = new com.rabbitmq.client.AMQP.BasicProperties();
            deliverRef.get().handle("consumer-tag-1",
                    new com.rabbitmq.client.Delivery(envelope, props, "hello".getBytes()));
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consumeAutoAckAutoAcksMessages() throws Exception {
        AtomicReference<DeliverCallback> deliverRef = new AtomicReference<>();
        when(channel.basicConsume(anyString(), eq(false), anyString(), eq(false), eq(false),
                anyMap(), any(DeliverCallback.class), any(com.rabbitmq.client.CancelCallback.class)))
                .thenAnswer(invocation -> {
                    deliverRef.set(invocation.getArgument(6));
                    return "consumer-tag-2";
                });

        receiver = new Receiver(new ReceiverOptions().connectionMono(Mono.just(connection)));

        var latch = new CountDownLatch(1);
        receiver.consumeAutoAck("test-queue")
                .subscribe(delivery -> latch.countDown());

        if (deliverRef.get() != null) {
            var envelope = new com.rabbitmq.client.Envelope(1L, false, "exch", "rk");
            var props = new com.rabbitmq.client.AMQP.BasicProperties();
            deliverRef.get().handle("consumer-tag-2",
                    new com.rabbitmq.client.Delivery(envelope, props, "hello".getBytes()));
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        verify(channel).basicAck(1L, false);
    }

    @Test
    void consumeManualAckEmitsErrorOnChannelCreationFailure() throws Exception {
        when(connection.createChannel()).thenThrow(new IOException("channel creation failed"));
        receiver = new Receiver(new ReceiverOptions().connectionMono(Mono.just(connection)));
        var publisher = receiver.consumeManualAck("test-queue");
        var timeout = Duration.ofSeconds(5);
        assertThatThrownBy(() -> publisher.blockLast(timeout))
                .isInstanceOf(RabbitFluxException.class);
    }

    @Test
    void closeIsIdempotent() {
        receiver = new Receiver(new ReceiverOptions().connectionMono(Mono.just(connection)));
        assertThatNoException().isThrownBy(() -> {
            receiver.close();
            receiver.close();
        });
        receiver = null; // prevent double close in tearDown
    }

    @Test
    void connectionClosedWithTimeout() throws Exception {
        Connection c = mock(Connection.class);
        Channel ch = mock(Channel.class);
        when(c.createChannel()).thenReturn(ch);
        when(ch.isOpen()).thenReturn(true);
        when(ch.getConnection()).thenReturn(c);
        var connectionLatch = new CountDownLatch(1);
        when(ch.basicConsume(anyString(), eq(false), anyString(), eq(false), eq(false),
                anyMap(), any(com.rabbitmq.client.DeliverCallback.class), any(com.rabbitmq.client.CancelCallback.class)
        ))
                .thenAnswer(inv -> {
                    connectionLatch.countDown();
                    return "tag";
                });

        var options = new ReceiverOptions()
                .connectionSupplier(cf -> c)
                .connectionClosingTimeout(Duration.ofMillis(5000));

        receiver = new Receiver(options);
        // Trigger connection establishment by subscribing
        receiver.consumeManualAck("test-queue").subscribe().dispose();
        assertThat(connectionLatch.await(5, TimeUnit.SECONDS)).isTrue();
        receiver.close();

        verify(c, times(1)).close(5000);
        receiver = null;
    }

    @Test
    void channelCallbackIsCalled() throws Exception {
        AtomicReference<DeliverCallback> deliverRef = new AtomicReference<>();
        when(channel.basicConsume(anyString(), eq(false), anyString(), eq(false), eq(false),
                anyMap(), any(DeliverCallback.class), any(com.rabbitmq.client.CancelCallback.class)))
                .thenAnswer(invocation -> {
                    deliverRef.set(invocation.getArgument(6));
                    return "consumer-tag-3";
                });

        receiver = new Receiver(new ReceiverOptions().connectionMono(Mono.just(connection)));

        var callbackCalled = new CountDownLatch(1);
        var consumeOptions = new ConsumeOptions().channelCallback(ch -> callbackCalled.countDown());

        var latch = new CountDownLatch(1);
        receiver.consumeManualAck("test-queue", consumeOptions)
                .subscribe(delivery -> {
                    delivery.ack();
                    latch.countDown();
                });

        if (deliverRef.get() != null) {
            var envelope = new com.rabbitmq.client.Envelope(1L, false, "exch", "rk");
            var props = new com.rabbitmq.client.AMQP.BasicProperties();
            deliverRef.get().handle("consumer-tag-3",
                    new com.rabbitmq.client.Delivery(envelope, props, "hello".getBytes()));
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(callbackCalled.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
