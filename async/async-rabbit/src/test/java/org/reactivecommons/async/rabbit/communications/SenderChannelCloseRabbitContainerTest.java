package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.SendOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails a publisher-confirms flow on the thread that reads the connection of a REAL RabbitMQ broker (a message is
 * returned without the header the flow adds to the messages it publishes), and checks where the channel of the flow is
 * closed. Closing a channel waits for the {@code close-ok} of the broker, which that very thread reads: on it, the
 * close cannot complete before the RPC timeout of the channels (10 minutes by default), and nothing else is read from
 * the connection meanwhile.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class SenderChannelCloseRabbitContainerTest {

    /**
     * Instead of the default 10 minutes, so that a close that cannot complete fails quickly.
     */
    private static final int CHANNEL_RPC_TIMEOUT_MILLIS = 2_000;

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Test
    void theChannelOfAFlowFailedByTheConnectionThreadIsClosedOffThatThread() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        factory.setChannelRpcTimeout(CHANNEL_RPC_TIMEOUT_MILLIS);
        try (Connection connection = factory.newConnection("sender-channel-close")) {
            Channel channel = connection.createChannel();
            Sender sender = new Sender(new SenderOptions().connectionMono(Mono.just(connection)));
            AtomicReference<String> closingThread = new AtomicReference<>();
            CompletableFuture<Long> closeMillis = new CompletableFuture<>();
            SendOptions options = new SendOptions()
                    .trackReturned(true)
                    .channelMono(Mono.just(channel))
                    .channelCloseHandler((signal, closing) -> {
                        closingThread.set(Thread.currentThread().getName());
                        long start = System.nanoTime();
                        try {
                            closing.close();
                        } catch (Exception e) {
                            // the close could not complete within the RPC timeout
                        }
                        closeMillis.complete(Duration.ofNanos(System.nanoTime() - start).toMillis());
                    });
            CompletableFuture<Throwable> failure = new CompletableFuture<>();
            Disposable flow = sender.sendWithTypedPublishConfirms(Flux.<OutboundMessage>never(), options)
                    .subscribe(null, failure::complete);

            // e.g. published on the channel of the flow by someone else: returned without the header of the flow
            channel.basicPublish("", "no-such-queue-" + UUID.randomUUID(), true, MessageProperties.BASIC,
                    "returned".getBytes(StandardCharsets.UTF_8));

            Throwable error = failure.get(10, TimeUnit.SECONDS);
            long millis = closeMillis.get(10, TimeUnit.SECONDS);
            System.out.printf("[REAL BROKER] flow failed with %s; its channel closed on %s in %d ms%n", error,
                    closingThread.get(), millis);
            flow.dispose();
            sender.close();
            assertThat(closingThread.get()).as("thread closing the channel of the failed flow")
                    .doesNotStartWith("AMQP Connection")
                    .doesNotStartWith("multiThreadIoEventLoopGroup");
            assertThat(millis).as("time to close the channel of the failed flow").isLessThan(1_000);
        }
    }
}
