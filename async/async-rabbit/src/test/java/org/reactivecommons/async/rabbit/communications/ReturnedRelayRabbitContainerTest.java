package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.SendFailureReturnedException;
import org.reactivecommons.async.rabbit.RabbitMessage;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relays, with a mandatory sender, a message consumed from a REAL RabbitMQ broker to a routing key no queue is bound
 * to. The consumed message still carries the {@code reactor_rabbitmq_delivery_tag} header its first publisher
 * (another application, also mandatory) used to correlate a {@code basic.return}: the relay must be reported as
 * returned, not as published.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReturnedRelayRabbitContainerTest {

    private static final String EXCHANGE = "relay.exchange";
    private static final String BOUND_KEY = "relay.bound";
    private static final String UNBOUND_KEY = "relay.nowhere";
    private static final String DELIVERY_TAG_HEADER = "reactor_rabbitmq_delivery_tag";

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private final MessageConverter converter = new RabbitJacksonMessageConverter(new JsonMapper());

    private Connection connection;
    private Channel admin;
    private String queue;
    private ReactiveMessageSender firstPublisher;
    private ReactiveMessageSender relay;

    @BeforeEach
    void init() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        connection = factory.newConnection("relay");
        admin = connection.createChannel();
        queue = "relay.queue." + UUID.randomUUID();
        admin.exchangeDeclare(EXCHANGE, "direct", true, false, null);
        admin.queueDeclare(queue, true, false, false, null);
        admin.queueBind(queue, EXCHANGE, BOUND_KEY);
        firstPublisher = mandatorySender();
        relay = mandatorySender();
    }

    private ReactiveMessageSender mandatorySender() {
        Sender sender = new Sender(new SenderOptions().channelPool(ChannelPoolFactory.createChannelPool(
                Mono.just(connection), new ChannelPoolOptions().maxCacheSize(10))));
        return new ReactiveMessageSender(sender, "TestApp", converter, null, true, new UnroutableMessageNotifier());
    }

    @AfterEach
    void tearDown() throws Exception {
        firstPublisher.close();
        relay.close();
        if (connection.isOpen()) {
            admin.queueDelete(queue);
            connection.close();
        }
    }

    @Test
    void aRelayedMessageThatIsReturnedIsReportedAsReturned() throws Exception {
        // the first publisher has published other messages: its publish sequence numbers are not the relay's ones
        Flux.range(0, 50)
                .flatMap(i -> firstPublisher.sendWithConfirm(Map.of("warm-up", i), EXCHANGE, BOUND_KEY, Map.of(), true))
                .blockLast(Duration.ofSeconds(30));
        admin.queuePurge(queue);
        firstPublisher.sendWithConfirm(Map.of("id", "original"), EXCHANGE, BOUND_KEY, Map.of(), true)
                .block(Duration.ofSeconds(30));
        GetResponse consumed = admin.basicGet(queue, true);
        RabbitMessage received = RabbitMessage.fromDelivery(
                new Delivery(consumed.getEnvelope(), consumed.getProps(), consumed.getBody()));

        Throwable relayError = relay.sendWithConfirm(received, EXCHANGE, UNBOUND_KEY, Map.of(), true)
                .timeout(Duration.ofSeconds(30))
                .flatMap(ignored -> Mono.<Throwable>empty())
                .onErrorResume(Mono::just)
                .block();

        System.out.printf("[REAL BROKER] consumed message %s=%s, relay to an unbound routing key: %s%n",
                DELIVERY_TAG_HEADER, received.getProperties().getHeaders().get(DELIVERY_TAG_HEADER),
                relayError == null ? "reported as published" : relayError);
        assertThat(relayError).as("a relayed message the broker returned")
                .isInstanceOf(SendFailureReturnedException.class);
    }
}
