package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.listeners.ApplicationQueueListener;
import org.reactivecommons.async.rabbit.listeners.ApplicationReplyListener;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;
import static org.reactivecommons.async.commons.Headers.CORRELATION_ID;

/**
 * Consumes from a REAL RabbitMQ broker with the {@link Receiver} on a recoverable connection, and makes the broker
 * close the consuming channel with a channel error: the automatic recovery only recovers channels along with their
 * connection, so the consumer must not stay subscribed to a dead channel.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReceiverChannelErrorRabbitContainerTest {

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private Connection connection;
    private Receiver receiver;
    private String queue;

    @BeforeEach
    void init() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        connection = factory.newConnection("receiver-channel-error");
        assertThat(connection).as("connection with automatic recovery").isInstanceOf(Recoverable.class);
        receiver = new Receiver(new ReceiverOptions().connectionMono(Mono.just(connection)));
        queue = "receiver.channel.error." + UUID.randomUUID();
        try (Channel admin = connection.createChannel()) {
            admin.queueDeclare(queue, true, false, false, null);
        }
    }

    @AfterEach
    void close() throws Exception {
        try (Channel admin = connection.createChannel()) {
            admin.queueDelete(queue);
        }
        connection.close();
    }

    @Test
    void aChannelErrorTerminatesTheConsumer() throws Exception {
        AtomicReference<Channel> consumingChannel = new AtomicReference<>();
        CompletableFuture<String> termination = new CompletableFuture<>();
        Disposable consumer = receiver.consumeManualAck(queue, new ConsumeOptions()
                        .channelCallback(consumingChannel::set))
                .doOnNext(delivery -> {
                    delivery.ack();
                    try {
                        // acknowledging the same delivery again: the broker closes the channel (406 unknown tag)
                        consumingChannel.get().basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .subscribe(delivery -> {
                }, error -> termination.complete("error: " + error), () -> termination.complete("completed"));
        try {
            publish("first");

            String outcome = termination.completeOnTimeout("still subscribed", 5, TimeUnit.SECONDS).get();
            ShutdownSignalException closeReason = consumingChannel.get().getCloseReason();
            System.out.printf("[REAL BROKER] consumer after a channel error: %s (channel open: %s, close reason: "
                    + "%s)%n", outcome, consumingChannel.get().isOpen(), closeReason);
            assertThat(consumingChannel.get().isOpen()).as("channel closed by the broker").isFalse();
            assertThat(closeReason.isHardError()).as("a channel error, not a connection error").isFalse();
            assertThat(outcome).as("consumer of the dead channel").startsWith("error: ")
                    .contains("PRECONDITION_FAILED");
        } finally {
            consumer.dispose();
        }
    }

    /**
     * The reply listener acknowledges each reply as it arrives, so the broker closing its channel with a channel error
     * is rare, but then no reply reaches the application until its connection is lost.
     */
    @Test
    void theReplyListenerKeepsReceivingRepliesAfterAChannelErrorOnItsChannel() throws Exception {
        List<Channel> consumingChannels = new CopyOnWriteArrayList<>();
        Receiver capturingReceiver = capturingReceiver(consumingChannels);
        Sender sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));
        ReactiveReplyRouter router = new ReactiveReplyRouter();
        String replyQueue = "replies." + UUID.randomUUID();
        String routingKey = replyQueue;
        ApplicationReplyListener listener = new ApplicationReplyListener(router,
                new ReactiveMessageListener(capturingReceiver, new TopologyCreator(sender, "classic")), replyQueue,
                "globalReply", true);
        listener.startListening(routingKey);
        try {
            awaitConsumers(replyQueue, 1);
            // a channel error on the consuming channel: the broker closes it (406 unknown delivery tag)
            consumingChannels.get(0).basicAck(999, false);
            await().atMost(Duration.ofSeconds(5)).until(() -> !consumingChannels.get(0).isOpen());
            awaitConsumers(replyQueue, 1); // the listener consumes again

            String correlationId = UUID.randomUUID().toString();
            Mono<Message> reply = router.register(correlationId);
            try (Channel channel = connection.createChannel()) {
                channel.basicPublish("globalReply", routingKey, new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                        .headers(Map.of(CORRELATION_ID, correlationId)).build(), "\"ok\"".getBytes());
            }
            String outcome = reply.map(message -> "received")
                    .timeout(Duration.ofSeconds(5), Mono.just("not received"))
                    .block();

            System.out.printf("[REAL BROKER] reply after a channel error on the reply listener: %s (consuming "
                    + "channels: %d, consumers: %d)%n", outcome, consumingChannels.size(), consumersOf(replyQueue));
            assertThat(outcome).as("reply sent after the channel error").isEqualTo("received");
            assertThat(consumersOf(replyQueue)).as("consumers of the reply queue").isEqualTo(1);
        } finally {
            listener.stop();
            sender.close();
        }
    }

    /**
     * The listener already restarts when its channel is closed while its connection stays open: terminating the
     * consumer as well must not restart it twice.
     */
    @Test
    void aQueueListenerRestartsOnceAfterAChannelErrorAndKeepsConsuming() throws Exception {
        List<Channel> consumingChannels = new CopyOnWriteArrayList<>();
        Sender sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));
        ReactiveMessageListener messageListener = new ReactiveMessageListener(capturingReceiver(consumingChannels),
                new TopologyCreator(sender, null), 10, 10);
        AtomicInteger received = new AtomicInteger();
        RegisteredQueueListener registered = HandlerRegistry.register()
                .listenQueue(queue, message -> Mono.fromRunnable(received::incrementAndGet))
                .getQueueHandlers().get(DEFAULT_DOMAIN).get(0);
        ApplicationQueueListener listener = new ApplicationQueueListener(messageListener, false, -1, 1000, registered,
                mock(DiscardNotifier.class), mock(CustomReporter.class));
        listener.startListener();
        try {
            awaitConsumers(queue, 1);
            // a channel error on the consuming channel: the broker closes it (406 unknown delivery tag)
            consumingChannels.get(0).basicAck(999, false);
            await().atMost(Duration.ofSeconds(5)).until(() -> !consumingChannels.get(0).isOpen());
            // a second restart, if any, would happen within its backoff
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                    .until(() -> consumingChannels.size() <= 2);

            publish("after the channel error");
            await().atMost(Duration.ofSeconds(5)).until(() -> received.get() >= 1);
            int consumers = consumersOf(queue);
            System.out.printf("[REAL BROKER] queue listener after a channel error: consuming channels=%d, "
                    + "consumers=%d, received=%d%n", consumingChannels.size(), consumers, received.get());
            assertThat(consumingChannels).as("channels of the listener: the first one and its single restart")
                    .hasSize(2);
            assertThat(consumers).as("consumers of the queue").isEqualTo(1);
            assertThat(received.get()).as("messages received after the channel error").isEqualTo(1);
        } finally {
            listener.stop();
            sender.close();
        }
    }

    /**
     * A receiver that records the channel of each consumer, so the test can make the broker close it.
     */
    private Receiver capturingReceiver(List<Channel> consumingChannels) {
        return new Receiver(new ReceiverOptions().connectionMono(Mono.just(connection))) {
            @Override
            public Flux<AcknowledgableDelivery> consumeManualAck(String queue, ConsumeOptions options) {
                var callback = options.getChannelCallback();
                options.channelCallback(channel -> {
                    if (callback != null) {
                        callback.accept(channel);
                    }
                    consumingChannels.add(channel);
                });
                return super.consumeManualAck(queue, options);
            }
        };
    }

    private void awaitConsumers(String queue, int expected) throws Exception {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(consumersOf(queue)).as("consumers of " + queue).isEqualTo(expected));
    }

    private int consumersOf(String queue) throws Exception {
        try (Channel channel = connection.createChannel()) {
            return channel.queueDeclarePassive(queue).getConsumerCount();
        } catch (java.io.IOException e) {
            return -1; // not declared yet
        }
    }

    private void publish(String body) throws Exception {
        try (Channel channel = connection.createChannel()) {
            channel.basicPublish("", queue, null, body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
