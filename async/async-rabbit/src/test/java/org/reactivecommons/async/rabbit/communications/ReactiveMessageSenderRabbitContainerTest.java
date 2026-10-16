package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ChannelPool;
import reactor.rabbitmq.ChannelPoolFactory;
import reactor.rabbitmq.ChannelPoolOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link ReactiveMessageSender} against a REAL RabbitMQ broker (Testcontainers) to show what happens when one
 * of the long-lived channels it publishes through is closed by the broker.
 *
 * <p>The channel failure is provoked the way it happens in production: publishing to an exchange that does not
 * exist. The broker answers {@code 404 NOT_FOUND} and closes ONLY that channel; the connection and the broker stay
 * perfectly healthy.
 *
 * <p>Like {@link ReactiveMessageSenderChannelFailureTest}, the assertions describe the CURRENT (defective) behavior
 * and must be inverted once the sender recovers from channel failures. The tests are skipped automatically when
 * Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReactiveMessageSenderRabbitContainerTest {

    private static final String EXCHANGE = "tc.exchange";
    private static final String ROUTING_KEY = "tc.rkey";
    private static final String MISSING_EXCHANGE = "tc.exchange.that.does.not.exist";

    private static final int CONTROL_PORT = 8474;
    private static final int PROXY_PORT = 8666;

    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("rabbit");

    /**
     * The sender connects to the broker THROUGH Toxiproxy, so tests can cut the network between sender and broker
     * (and make the broker "go silent") without touching the broker itself. The admin connection goes direct.
     */
    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0")
            .withNetwork(NETWORK)
            .withExposedPorts(CONTROL_PORT, PROXY_PORT);

    private final MessageConverter converter = new RabbitJacksonMessageConverter(new JsonMapper());


    //    export DOCKER_HOST=unix:///Users/lugomez/.colima/default/docker.sock
//    export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
//            ./gradlew :async-rabbit:cleanTest :async-rabbit:test --tests '*ReactiveMessageSenderRabbitContainerTest'
    private Connection senderConnection;
    private Connection adminConnection;
    private Channel admin;
    private Proxy proxy;
    private String queue;
    private ReactiveMessageSender messageSender;

    @BeforeEach
    void init() throws Exception {
        // Topology created with an independent, direct connection, so it is not affected by what happens to the sender.
        ConnectionFactory adminFactory = new ConnectionFactory();
        adminFactory.setHost(RABBIT.getHost());
        adminFactory.setPort(RABBIT.getAmqpPort());
        adminFactory.setUsername(RABBIT.getAdminUsername());
        adminFactory.setPassword(RABBIT.getAdminPassword());
        adminConnection = adminFactory.newConnection("tc-admin");
        admin = adminConnection.createChannel();
        queue = "tc.queue." + UUID.randomUUID();
        admin.exchangeDeclare(EXCHANGE, "direct", true, false, null);
        // RabbitMQ 4 rejects transient non-exclusive queues (feature `transient_nonexcl_queues` is deprecated),
        // so the queue must be durable.
        admin.queueDeclare(queue, true, false, false, null);
        admin.queueBind(queue, EXCHANGE, ROUTING_KEY);

        proxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                .createProxy("rabbit", "0.0.0.0:" + PROXY_PORT, "rabbit:5672");

        // Same wiring as RabbitMQSetupUtils: ONE shared connection (with the client's automatic recovery, as in
        // production) and a channel pool for the sender.
        ConnectionFactory senderFactory = new ConnectionFactory();
        senderFactory.setHost(TOXIPROXY.getHost());
        senderFactory.setPort(TOXIPROXY.getMappedPort(PROXY_PORT));
        senderFactory.setUsername(RABBIT.getAdminUsername());
        senderFactory.setPassword(RABBIT.getAdminPassword());
        senderFactory.setNetworkRecoveryInterval(1_000); // library default is 5s, shortened to speed up the tests
        senderConnection = senderFactory.newConnection("tc-sender");
        Mono<Connection> connectionMono = Mono.just(senderConnection);
        ChannelPool channelPool = ChannelPoolFactory.createChannelPool(connectionMono,
                new ChannelPoolOptions().maxCacheSize(5));
        Sender sender = new Sender(new SenderOptions().channelPool(channelPool));
        messageSender = new ReactiveMessageSender(sender, "TestApp", converter, null, false,
                new UnroutableMessageNotifier());

        // The 4 publisher-confirm flows (and the "no confirm" one) subscribe asynchronously because they first have
        // to obtain a channel. A sendWithConfirm issued before all of them registered their sink picks an index that
        // does not exist yet: the IndexOutOfBoundsException is swallowed by the executor and the returned Mono never
        // finishes (observed: 18 of 20 confirmed right after start-up). Give the flows time to register, so the
        // tests below only measure the failure scenarios they are about.
        Thread.sleep(1_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            senderConnection.close();
        } catch (Exception ignored) {
            // the connection may already be broken by the test
        }
        proxy.delete();
        admin.queueDelete(queue);
        adminConnection.close();
    }

    @Test
    void sendWithConfirmNeverFinishesAfterTheBrokerClosesOneOfTheSenderChannels() throws Exception {
        // 1. Healthy broker: everything is confirmed and routed to the queue.
        Counters healthy = sendMessages(100, EXCHANGE);
        awaitSettled(healthy, 100);
        assertThat(healthy.completed.get()).as("healthy broker: confirmed messages").isEqualTo(100);
        assertThat(queueSize()).isEqualTo(100);

        // 2. Poison message: unknown exchange => broker closes the channel with 404. Only one of the 4
        //    publisher-confirm flows is affected, and which one is random.
        messageSender.sendWithConfirm("poison", MISSING_EXCHANGE, ROUTING_KEY, new HashMap<>(), true)
                .subscribe(v -> {
                }, e -> {
                });
        Thread.sleep(1_000); // let the broker close the channel and the client notify the shutdown

        // 3. Broker and connection are healthy, nothing else is wrong, yet part of the sends never finish.
        int total = 200;
        Counters afterFailure = sendMessages(total, EXCHANGE);
        awaitSettled(afterFailure, total);

        int completed = afterFailure.completed.get();
        int failed = afterFailure.failed.get();
        int hanging = total - completed - failed;
        System.out.printf("[REAL BROKER] after 404 channel close: sent=%d completed=%d failed=%d HANGING=%d%n",
                total, completed, failed, hanging);

        assertThat(completed).as("messages routed to healthy subscriptions still work").isGreaterThan(0);
        assertThat(failed).as("no error is ever reported to the caller").isZero();
        assertThat(hanging)
                .as("messages routed to the dead subscription never get success nor error (~1/4)")
                .isGreaterThan(0);
        assertThat(queueSize())
                .as("the hanging messages never reached the broker: only the confirmed ones did")
                .isEqualTo(100 + completed);
    }

    @Test
    void sendNoConfirmLosesEverythingAfterTheBrokerClosesItsChannel() throws Exception {
        // 0. With a real connection the "no confirm" flow subscribes asynchronously (it has to obtain a channel
        //    first), so `fluxSinkNoConfirm` is null for a few milliseconds after construction and an early
        //    sendNoConfirm fails with a NullPointerException. Wait until the flow is ready.
        awaitNoConfirmFlowReady();

        // 1. Healthy broker.
        for (int i = 0; i < 5; i++) {
            messageSender.sendNoConfirm("ok-" + i, EXCHANGE, ROUTING_KEY, new HashMap<>(), true).block();
        }
        Thread.sleep(500);
        assertThat(queueSize()).isEqualTo(5);

        // 2. Poison message: the broker closes the (single) channel used by the "no confirm" flow.
        messageSender.sendNoConfirm("poison", MISSING_EXCHANGE, ROUTING_KEY, new HashMap<>(), true).block();
        Thread.sleep(1_000);

        // 3. Next publishes fail on the closed channel. The default exception handler retries for 10 seconds
        //    ON THE CALLER THREAD (Thread.sleep) and then the flow terminates with an error.
        //    Run it in another thread to measure how long the caller is blocked.
        AtomicInteger blockedMillis = new AtomicInteger();
        Thread caller = new Thread(() -> {
            long start = System.currentTimeMillis();
            try {
                messageSender.sendNoConfirm("after-failure", EXCHANGE, ROUTING_KEY, new HashMap<>(), true).block();
            } catch (Throwable t) {
                System.out.println("[REAL BROKER] sendNoConfirm threw: " + t);
            }
            blockedMillis.set((int) (System.currentTimeMillis() - start));
        }, "no-confirm-caller");
        caller.start();
        caller.join(30_000);
        System.out.printf("[REAL BROKER] first sendNoConfirm after the failure blocked its caller for %d ms%n",
                blockedMillis.get());

        // 4. The channel is NOT recovered: the flow is dead, but every call reports success.
        for (int i = 0; i < 20; i++) {
            messageSender.sendNoConfirm("lost-" + i, EXCHANGE, ROUTING_KEY, new HashMap<>(), true).block();
        }
        Thread.sleep(1_000);

        int inQueue = queueSize();
        System.out.printf("[REAL BROKER] sendNoConfirm calls after failure: 21 (all reported success), "
                + "messages that reached the broker: %d%n", inQueue - 5);
        assertThat(inQueue)
                .as("none of the 21 messages sent after the channel failure reached the broker")
                .isEqualTo(5);
    }

    /**
     * Point (e): messages that were published but not yet confirmed when the whole CONNECTION is lost.
     *
     * <p>Toxiproxy silences the broker-to-client direction (so publisher confirms never arrive) while the
     * messages still reach the broker. Then the connection is cut and restored, and the client's automatic
     * recovery brings the connection and channels back.
     */
    @Test
    void sendWithConfirmInFlightDuringConnectionLossNeverFinishesAndNumberingIsCorrupted() throws Exception {
        // 1. Healthy: 20 confirmed messages.
        Counters healthy = sendMessages(20, EXCHANGE);
        awaitSettled(healthy, 20);
        assertThat(healthy.completed.get()).isEqualTo(20);
        assertThat(queueSize()).isEqualTo(20);

        // 2. The broker goes silent towards the client: publishes reach it, confirms do not come back.
        Toxic silence = proxy.toxics().timeout("silence-broker", ToxicDirection.DOWNSTREAM, 0);
        int inFlight = 50;
        Counters inDoubt = sendMessages(inFlight, EXCHANGE);
        Thread.sleep(1_500);
        System.out.printf("[REAL BROKER] in flight: sent=%d, confirmed to caller=%d, already stored by broker=%d%n",
                inFlight, inDoubt.completed.get(), queueSize() - 20);
        assertThat(inDoubt.completed.get()).as("no confirm can arrive while the broker is silent").isZero();

        // 3. Connection loss: the network is cut, the sender sees the connection die, then the network is restored.
        proxy.disable();
        Thread.sleep(2_000);
        silence.remove();
        proxy.enable();
        awaitConnectionRecovered();
        Thread.sleep(2_000);

        int pendingAfterRecovery = inFlight - inDoubt.completed.get() - inDoubt.failed.get();
        int storedByBroker = queueSize() - 20;
        System.out.printf("[REAL BROKER] connection recovered. In-flight messages: completed=%d failed=%d "
                        + "STILL PENDING=%d (broker stored %d of them)%n",
                inDoubt.completed.get(), inDoubt.failed.get(), pendingAfterRecovery, storedByBroker);

        // The in-flight messages are in doubt: the broker HAS them, but the caller is never told anything
        // (no ack, no nack, no error, no timeout), so it cannot decide whether to retry.
        assertThat(storedByBroker).as("the broker stored every in-flight message").isEqualTo(inFlight);
        assertThat(pendingAfterRecovery).as("callers are never notified, not even after the recovery")
                .isEqualTo(inFlight);
        assertThat(inDoubt.failed.get()).isZero();

        // 4. After the recovery the sender is used normally.
        int afterTotal = 100;
        Counters afterRecovery = sendMessages(afterTotal, EXCHANGE);
        awaitSettled(afterRecovery, afterTotal);
        Thread.sleep(2_000);

        int newHanging = afterTotal - afterRecovery.completed.get() - afterRecovery.failed.get();
        int oldCompletedLater = inDoubt.completed.get();
        int brokerQueue = queueSize();
        System.out.printf("[REAL BROKER] after recovery: new sends=%d completed=%d failed=%d HANGING=%d | "
                        + "old in-flight messages: completed=%d failed=%d still pending=%d | broker queue=%d%n",
                afterTotal, afterRecovery.completed.get(), afterRecovery.failed.get(), newHanging,
                oldCompletedLater, inDoubt.failed.get(), inFlight - oldCompletedLater - inDoubt.failed.get(),
                brokerQueue);

        // The recovered channels restart the publish sequence numbers at 1, but PublishConfirmSubscriber keeps the
        // stale in-flight entries in its `unconfirmed` map and registers new messages with putIfAbsent(seqNo).
        // The broker's confirms for the NEW messages are therefore delivered to the OLD (stale) messages:
        assertThat(brokerQueue)
                .as("the broker really received and confirmed every new message")
                .isEqualTo(20 + inFlight + afterTotal);
        assertThat(newHanging)
                .as("new messages that the broker stored and confirmed are reported to the caller as never finished")
                .isGreaterThan(0);
        assertThat(oldCompletedLater)
                .as("old in-flight Monos complete only because they received the confirms of OTHER messages")
                .isGreaterThan(0);
        assertThat(afterRecovery.failed.get() + inDoubt.failed.get())
                .as("a nack would be attributed to the wrong message too, and no error is ever raised")
                .isZero();
    }

    // ---------------------------------------------------------------------------------------------------------

    private void awaitConnectionRecovered() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!senderConnection.isOpen() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(senderConnection.isOpen()).as("client automatic recovery restored the connection").isTrue();
    }

    private record Counters(AtomicInteger completed, AtomicInteger failed) {
    }

    private Counters sendMessages(int total, String exchange) throws InterruptedException {
        Counters counters = new Counters(new AtomicInteger(), new AtomicInteger());
        for (int i = 0; i < total; i++) {
            messageSender.sendWithConfirm("payload-" + i, exchange, ROUTING_KEY, new HashMap<>(), true)
                    .subscribe(v -> {
                    }, e -> counters.failed.incrementAndGet(), counters.completed::incrementAndGet);
            // ReactiveMessageSender picks the subscription with System.currentTimeMillis() % 4,
            // so spread the sends over time to reach every subscription.
            Thread.sleep(1);
        }
        return counters;
    }

    private void awaitSettled(Counters counters, int total) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && counters.completed.get() + counters.failed.get() < total) {
            Thread.sleep(50);
        }
    }

    private void awaitNoConfirmFlowReady() throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (true) {
            try {
                messageSender.sendNoConfirm("warm-up", EXCHANGE, ROUTING_KEY, new HashMap<>(), true).block();
                break;
            } catch (NullPointerException notReadyYet) {
                if (System.currentTimeMillis() > deadline) {
                    throw notReadyYet;
                }
                Thread.sleep(20);
            }
        }
        // The warm-up message is routed to the queue: drain it so the counts below start from zero.
        Thread.sleep(300);
        admin.queuePurge(queue);
    }

    private int queueSize() throws Exception {
        return admin.queueDeclarePassive(queue).getMessageCount();
    }
}
