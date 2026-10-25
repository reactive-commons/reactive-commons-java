package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.commons.exceptions.SendFailureMessageTooLargeException;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ChannelPoolFactory;
import reactor.rabbitmq.ChannelPoolOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Publishes a message larger than the broker's {@code max_message_size} (16 MiB by default in RabbitMQ 4) through
 * {@link ReactiveMessageSender} against a REAL broker. The broker answers such a publish by closing the whole channel
 * ({@code 406 PRECONDITION_FAILED}), which the publishing flows share with the messages of other callers: the
 * oversized message must be rejected before it is written, and the other messages must not be affected.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class MessageSizeLimitRabbitContainerTest {

    private static final String EXCHANGE = "size.exchange";
    private static final String ROUTING_KEY = "size.rkey";
    /**
     * Over the 16 MiB default limit of RabbitMQ 4 once serialized.
     */
    private static final Map<String, String> OVERSIZED = Map.of("data", "a".repeat(17 * 1024 * 1024));
    private static final int SMALL_MESSAGES = 200;

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private final MessageConverter converter = new RabbitJacksonMessageConverter(new JsonMapper());

    private Connection connection;
    private Channel admin;
    private String queue;
    private ReactiveMessageSender messageSender;

    @BeforeEach
    void init() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        connection = factory.newConnection("size-limit");
        admin = connection.createChannel();
        queue = "size.queue." + UUID.randomUUID();
        admin.exchangeDeclare(EXCHANGE, "direct", true, false, null);
        admin.queueDeclare(queue, true, false, false, null);
        admin.queueBind(queue, EXCHANGE, ROUTING_KEY);

        Sender sender = new Sender(new SenderOptions().channelPool(ChannelPoolFactory.createChannelPool(
                Mono.just(connection), new ChannelPoolOptions().maxCacheSize(5))));
        messageSender = new ReactiveMessageSender(sender, "TestApp", converter, null, false,
                new UnroutableMessageNotifier());
    }

    @AfterEach
    void tearDown() throws Exception {
        messageSender.close();
        if (connection.isOpen()) {
            admin.queueDelete(queue);
            connection.close();
        }
    }

    @Test
    void anOversizedMessageWithConfirmIsRejectedWithoutAffectingTheOtherMessagesOfItsChannel() throws Exception {
        AtomicInteger acked = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Flux<Void> smallMessages = Flux.range(0, SMALL_MESSAGES)
                .flatMap(i -> messageSender.sendWithConfirm(Map.of("id", i), EXCHANGE, ROUTING_KEY, Map.of(), true)
                        .doOnSuccess(ignored -> acked.incrementAndGet())
                        .onErrorResume(error -> {
                            failed.incrementAndGet();
                            return Mono.empty();
                        }), 50);
        AtomicReference<Throwable> oversizedError = new AtomicReference<>();
        Mono<Void> oversized = messageSender.sendWithConfirm(OVERSIZED, EXCHANGE, ROUTING_KEY, Map.of(), true)
                .onErrorResume(error -> {
                    oversizedError.set(error);
                    return Mono.empty();
                });

        Flux.merge(smallMessages, oversized).blockLast(Duration.ofSeconds(60));

        System.out.printf("[REAL BROKER] oversized message error=%s, small messages acked=%d failed=%d, confirm "
                        + "flow failures=%d%n", oversizedError.get(), acked.get(), failed.get(),
                messageSender.publisherStatus().consecutiveConfirmFailures());
        assertThat(oversizedError.get()).as("a message that was never published is not an unknown outcome")
                .isInstanceOf(SendFailureMessageTooLargeException.class)
                .isNotInstanceOf(SendConfirmUnknownException.class);
        assertThat(messageSender.publisherStatus().consecutiveConfirmFailures())
                .as("publishing flows whose channel the broker closed").isZero();
        assertThat(acked.get()).as("small messages acknowledged").isEqualTo(SMALL_MESSAGES);
        assertThat(messagesInQueue()).as("messages that reached the queue").isEqualTo(SMALL_MESSAGES);
    }

    @Test
    void anOversizedMessageWithoutConfirmIsRejectedInsteadOfBeingReportedAsPublished() throws Exception {
        assertThatThrownBy(() -> messageSender.sendNoConfirm(OVERSIZED, EXCHANGE, ROUTING_KEY, Map.of(), false)
                .block(Duration.ofSeconds(30)))
                .as("an oversized message the broker drops must not be reported as published")
                .isInstanceOf(SendFailureMessageTooLargeException.class);

        // the no-confirm flow keeps its channel: the next message is published
        messageSender.sendNoConfirm(Map.of("id", 1), EXCHANGE, ROUTING_KEY, Map.of(), false)
                .block(Duration.ofSeconds(30));
        long deadline = System.currentTimeMillis() + 10_000;
        while (messagesInQueue() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(messagesInQueue()).as("messages that reached the queue").isEqualTo(1);
    }

    private long messagesInQueue() throws Exception {
        return admin.queueDeclarePassive(queue).getMessageCount();
    }
}
