package org.reactivecommons.async.rabbit.communications;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ExceptionHandlers;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.SendOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * Acknowledges and publishes, with the default options of the {@link Receiver} and of the {@link Sender}, while the
 * client recovers a connection that a REAL RabbitMQ broker closed. A default handler must neither block the thread
 * that acknowledges or publishes (it can be a thread that must never block, e.g. an event loop) nor report a failure
 * as a success: an acknowledgment of a delivery of a closed channel is not sent (the broker redelivers the message),
 * even once the channel is recovered.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class DefaultExceptionHandlersRabbitContainerTest {

    private static final int RECOVERY_INTERVAL_MILLIS = 2_000;
    private static final String CONNECTION_NAME = "default-exception-handlers";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private Connection connection;
    private String queue;
    private ListAppender<ILoggingEvent> warnings;

    @BeforeEach
    void init() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        factory.setNetworkRecoveryInterval(RECOVERY_INTERVAL_MILLIS);
        connection = factory.newConnection(CONNECTION_NAME);
        queue = "default.exception.handlers." + UUID.randomUUID();
        try (Channel admin = connection.createChannel()) {
            admin.queueDeclare(queue, true, false, false, null);
        }
        warnings = new ListAppender<>();
        warnings.start();
        ((Logger) LoggerFactory.getLogger(ExceptionHandlers.class)).addAppender(warnings);
    }

    @AfterEach
    void close() throws Exception {
        ((Logger) LoggerFactory.getLogger(ExceptionHandlers.class)).detachAppender(warnings);
        await().atMost(Duration.ofSeconds(30)).until(connection::isOpen);
        try (Channel admin = connection.createChannel()) {
            admin.queueDelete(queue);
        }
        connection.close();
    }

    @Test
    void anAcknowledgmentOfADeliveryOfAClosedChannelNeitherBlocksNorPassesForSent() throws Exception {
        try (Channel admin = connection.createChannel()) {
            admin.basicPublish("", queue, MessageProperties.PERSISTENT_BASIC,
                    "message".getBytes(StandardCharsets.UTF_8));
        }
        Receiver receiver = new Receiver(new ReceiverOptions().connectionMono(Mono.just(connection)));
        CompletableFuture<AcknowledgableDelivery> received = new CompletableFuture<>();
        Disposable consumer = receiver.consumeManualAck(queue).subscribe(received::complete);
        AcknowledgableDelivery delivery = received.get(10, TimeUnit.SECONDS);
        closeTheConnectionFromTheBroker();

        long start = System.nanoTime();
        Throwable error = catchThrowable(delivery::ack);
        long ackMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        System.out.printf("[REAL BROKER] acknowledgment of a delivery of a closed channel: returned after %d ms, "
                + "error=%s, warnings=%s%n", ackMillis, error, warnings.list);
        consumer.dispose();
        receiver.close();
        assertThat(ackMillis).as("time the acknowledgment blocked its thread").isLessThan(500);
        assertThat(error != null || warnings.list.stream().anyMatch(event -> event.getLevel() == Level.WARN))
                .as("the acknowledgment that was not sent is reported").isTrue();
    }

    @Test
    void aPublishOnAConnectionBeingRecoveredDoesNotBlockItsThread() throws Exception {
        Channel channel = connection.createChannel();
        Sender sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));
        closeTheConnectionFromTheBroker();

        long start = System.nanoTime();
        Throwable error = catchThrowable(() -> sender.send(
                        Mono.just(new OutboundMessage("", queue, "message".getBytes(StandardCharsets.UTF_8))),
                        new SendOptions().channelMono(Mono.just(channel)))
                .block(Duration.ofSeconds(30)));
        long publishMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        System.out.printf("[REAL BROKER] publish while the connection is being recovered: returned after %d ms, "
                + "error=%s%n", publishMillis, error);
        sender.close();
        assertThat(publishMillis).as("time the publish blocked its thread").isLessThan(500);
        assertThat(error).as("publish that could not be sent").isNotNull();
    }

    /**
     * The broker closes the connection: the client re-opens it once {@link #RECOVERY_INTERVAL_MILLIS} have elapsed.
     */
    private void closeTheConnectionFromTheBroker() throws Exception {
        RABBIT.execInContainer("rabbitmqctl", "close_all_connections", "test: connection lost");
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(10)).until(() -> !connection.isOpen());
    }
}
