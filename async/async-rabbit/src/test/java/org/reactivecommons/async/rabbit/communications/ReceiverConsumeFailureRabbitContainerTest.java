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
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts consuming with the {@link Receiver} from a REAL RabbitMQ broker when a callback of the consumer fails: the
 * consumer fails, and must not leave its channel open (nor, when the broker already registered it, a consumer that
 * keeps receiving deliveries nobody acknowledges).
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReceiverConsumeFailureRabbitContainerTest {

    private static final String CONNECTION_NAME = "receiver-consume-failure";

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
        connection = factory.newConnection(CONNECTION_NAME);
        receiver = new Receiver(new ReceiverOptions().connectionMono(Mono.just(connection)));
        queue = "receiver.consume.failure." + UUID.randomUUID();
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
    void aFailingChannelCallbackLeavesNoChannelOpen() throws Exception {
        String outcome = consume(new ConsumeOptions().channelCallback(channel -> {
            throw new IllegalStateException("channel callback failure");
        }));

        System.out.printf("[REAL BROKER] channel callback failure: %s%n", outcome);
        assertThat(outcome).isEqualTo("channels=0, consumers=0");
    }

    @Test
    void aFailingRegistrationCallbackLeavesNoConsumerNorChannel() throws Exception {
        String outcome = consume(new ConsumeOptions().consumerRegisteredCallback(tag -> {
            throw new IllegalStateException("registration callback failure");
        }));

        System.out.printf("[REAL BROKER] registration callback failure: %s%n", outcome);
        assertThat(outcome).isEqualTo("channels=0, consumers=0");
    }

    /**
     * The channels the broker reports for the connection and the consumers of the queue, once the consumer failed.
     */
    private String consume(ConsumeOptions options) throws Exception {
        Throwable error = receiver.consumeManualAck(queue, options)
                .then(Mono.<Throwable>empty())
                .onErrorResume(Mono::just)
                .block(Duration.ofSeconds(10));
        assertThat(error).as("the consumer fails").isNotNull();
        Thread.sleep(1_000); // the broker reports the channels of a connection with a short delay
        return "channels=" + channelsOfTheConnection() + ", consumers=" + consumersOfTheQueue();
    }

    private static String channelsOfTheConnection() throws Exception {
        return RABBIT.execInContainer("rabbitmqctl", "-q", "list_connections", "client_properties", "channels")
                .getStdout().lines()
                .filter(line -> line.contains("\"" + CONNECTION_NAME + "\""))
                .map(line -> line.substring(line.lastIndexOf('\t') + 1).trim())
                .findFirst().orElse("?");
    }

    private String consumersOfTheQueue() throws Exception {
        try (Channel admin = connection.createChannel()) {
            return String.valueOf(admin.queueDeclarePassive(queue).getConsumerCount());
        }
    }
}
