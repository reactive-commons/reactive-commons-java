package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ChannelPoolFactory;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Consumes and publishes on a connection to a REAL RabbitMQ broker that already has every channel it may open (its
 * {@code channel_max}): the client then returns no channel, and the failure must say why instead of being a
 * {@link NullPointerException} raised further on.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ChannelMaxRabbitContainerTest {

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private Connection connection;
    private Channel onlyChannel;
    private final String queue = "channel.max." + UUID.randomUUID();

    @BeforeEach
    void init() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        factory.setRequestedChannelMax(1);
        connection = factory.newConnection("channel-max");
        onlyChannel = connection.createChannel();
        onlyChannel.queueDeclare(queue, false, true, true, null);
    }

    @AfterEach
    void close() throws Exception {
        connection.close();
    }

    @Test
    void consumingWithoutAChannelAvailableFailsSayingWhy() {
        Receiver receiver = new Receiver(new ReceiverOptions().connectionMono(Mono.just(connection)));

        Throwable error = catchThrowable(() -> receiver.consumeAutoAck(queue).blockFirst(Duration.ofSeconds(10)));

        System.out.printf("[REAL BROKER] consuming at channel_max: %s%n", error);
        receiver.close();
        assertFailsSayingWhy(error);
    }

    @Test
    void publishingWithoutAChannelAvailableFailsSayingWhy() {
        Sender sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));

        Throwable error = catchThrowable(() -> sender.send(Mono.just(message())).block(Duration.ofSeconds(10)));

        System.out.printf("[REAL BROKER] publishing at channel_max: %s%n", error);
        sender.close();
        assertFailsSayingWhy(error);
    }

    @Test
    void publishingFromAChannelPoolWithoutAChannelAvailableFailsSayingWhy() {
        Mono<Connection> connectionMono = Mono.just(connection);
        Sender sender = new Sender(new SenderOptions().connectionMono(connectionMono)
                .channelPool(ChannelPoolFactory.createChannelPool(connectionMono)));

        Throwable error = catchThrowable(() -> sender.send(Mono.just(message())).block(Duration.ofSeconds(10)));

        System.out.printf("[REAL BROKER] publishing from a channel pool at channel_max: %s%n", error);
        sender.close();
        assertFailsSayingWhy(error);
    }

    private OutboundMessage message() {
        return new OutboundMessage("", queue, "message".getBytes(StandardCharsets.UTF_8));
    }

    private static void assertFailsSayingWhy(Throwable error) {
        assertThat(error).as("failure without a channel available").isNotNull()
                .isNotInstanceOf(NullPointerException.class);
        assertThat(error).hasStackTraceContaining("channel_max");
    }
}
