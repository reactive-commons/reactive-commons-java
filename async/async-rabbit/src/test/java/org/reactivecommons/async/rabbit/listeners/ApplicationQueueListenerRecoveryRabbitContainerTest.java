package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

/**
 * Runs several {@code listenQueue} listeners on the same connection against a REAL RabbitMQ broker (Testcontainers)
 * and checks that all of them keep consuming after the client automatically recovers the connection.
 *
 * <p>With automatic recovery, consumer tags must be unique across all the channels of a connection: the client
 * records the consumers to recover by consumer tag, so consumers sharing a tag overwrite each other and only the last
 * one is recovered (see the RabbitMQ Java client guide, "Failure Detection and Recovery Limitations").
 * The test is skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplicationQueueListenerRecoveryRabbitContainerTest {

    private static final String CONNECTION_NAME = "tc-listeners";
    private static final int QUEUES = 3;

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private final Map<String, AtomicInteger> received = new ConcurrentHashMap<>();
    private Connection listenerConnection;
    private List<String> queues;

    @BeforeEach
    void init() throws Exception {
        String suffix = UUID.randomUUID().toString();
        queues = IntStream.range(0, QUEUES).mapToObj(i -> "tc.listen.queue" + i + "." + suffix)
                .toList();
        withAdminChannel(admin -> {
            for (String queue : queues) {
                admin.queueDeclare(queue, true, false, false, null);
            }
        });

        ConnectionFactory factory = factory();
        factory.setNetworkRecoveryInterval(1_000); // library default is 5s, shortened to speed up the test
        listenerConnection = factory.newConnection(CONNECTION_NAME); // automatic recovery enabled (client default)
    }

    @AfterEach
    void tearDown() throws Exception {
        listenerConnection.abort(2_000);
        withAdminChannel(admin -> {
            for (String queue : queues) {
                admin.queueDelete(queue);
            }
        });
    }

    @Test
    void everyQueueKeepsConsumingAfterTheConnectionIsRecovered() throws Exception {
        // Same wiring as RabbitMQSetupUtils.createMessageListener: one shared connection for all the listeners.
        Mono<Connection> connection = Mono.just(listenerConnection);
        ReactiveMessageListener messageListener = new ReactiveMessageListener(
                new Receiver(new ReceiverOptions().connectionMono(connection)),
                new TopologyCreator(new Sender(new SenderOptions().connectionMono(connection)), null),
                10, 10);
        HandlerRegistry registry = HandlerRegistry.register();
        for (String queue : queues) {
            received.put(queue, new AtomicInteger());
            registry.listenQueue(queue, message -> Mono.fromRunnable(() -> received.get(queue).incrementAndGet()));
        }
        for (RegisteredQueueListener registered : registry.getQueueHandlers().get(DEFAULT_DOMAIN)) {
            new ApplicationQueueListener(messageListener, false, -1, 1000, registered,
                    mock(DiscardNotifier.class), mock(CustomReporter.class)).startListener();
        }

        // 1. Healthy: every queue consumes.
        publishToEveryQueue(5);
        awaitReceived(5);
        System.out.println("[REAL BROKER] consumers before the connection loss: " + consumers());

        // 2. The broker closes the connection (e.g. node restart): the client recovers it automatically.
        RABBIT.execInContainer("rabbitmqctl", "close_all_connections", "recovery test");
        awaitConnectionRecovered();
        Thread.sleep(2_000); // let the client recover channels and consumers
        String consumersAfter = consumers();
        System.out.println("[REAL BROKER] consumers after the recovery: " + consumersAfter);

        // 3. Every queue keeps consuming.
        publishToEveryQueue(5);
        awaitReceived(10);
        System.out.println("[REAL BROKER] received per queue: " + received);
        assertThat(received.values()).as("messages received by each queue listener")
                .allSatisfy(count -> assertThat(count.get()).isEqualTo(10));
    }

    private void publishToEveryQueue(int messages) throws Exception {
        withAdminChannel(admin -> {
            for (String queue : queues) {
                for (int i = 0; i < messages; i++) {
                    admin.basicPublish("", queue, null, ("m" + i).getBytes(StandardCharsets.UTF_8));
                }
            }
        });
    }

    private void awaitReceived(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline
                && received.values().stream().anyMatch(count -> count.get() < expected)) {
            Thread.sleep(100);
        }
    }

    private void awaitConnectionRecovered() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        Thread.sleep(500); // the close must be noticed first
        while (!listenerConnection.isOpen() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(listenerConnection.isOpen()).as("client automatic recovery restored the connection").isTrue();
    }

    private String consumers() throws Exception {
        return RABBIT.execInContainer("rabbitmqctl", "-q", "list_consumers", "queue_name", "consumer_tag")
                .getStdout().trim().replace('\n', ';');
    }

    private interface AdminAction {
        void run(Channel admin) throws Exception;
    }

    private static void withAdminChannel(AdminAction action) throws Exception {
        try (Connection admin = factory().newConnection("tc-admin");
             Channel channel = admin.createChannel()) {
            action.run(channel);
        }
    }

    private static ConnectionFactory factory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        return factory;
    }
}
