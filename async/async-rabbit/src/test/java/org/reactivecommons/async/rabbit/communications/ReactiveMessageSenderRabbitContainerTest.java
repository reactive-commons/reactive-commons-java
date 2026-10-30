package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ChannelPool;
import reactor.rabbitmq.ChannelPoolFactory;
import reactor.rabbitmq.ChannelPoolOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Runs {@link ReactiveMessageSender} against a REAL RabbitMQ broker (Testcontainers) and checks that it recovers
 * from the failures seen in production:
 * <ul>
 *     <li>a channel closed by the broker: publishing to an exchange that does not exist makes the broker answer
 *     {@code 404 NOT_FOUND} and close ONLY that channel, the connection stays healthy;</li>
 *     <li>a connection lost with messages waiting for their publisher confirms (via Toxiproxy);</li>
 *     <li>sends issued right after start-up.</li>
 * </ul>
 * The broker queue size is the source of truth for what really reached the broker. The tests are skipped
 * automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReactiveMessageSenderRabbitContainerTest {

    private static final String EXCHANGE = "tc.exchange";
    private static final String ROUTING_KEY = "tc.rkey";
    private static final String MISSING_EXCHANGE = "tc.exchange.that.does.not.exist";
    private static final String SENDER_CONNECTION_NAME = "tc-sender";

    private static final int CONTROL_PORT = 8474;
    private static final int PROXY_PORT = 8666;

    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("rabbit");

    /**
     * The sender connects to the broker THROUGH Toxiproxy, so tests can cut the network between sender and broker
     * (and make the broker "go silent") without touching the broker itself. The admin connection goes direct.
     */
    @Container
    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy")
            .withNetwork(NETWORK)
            .withExposedPorts(CONTROL_PORT, PROXY_PORT);

    private final MessageConverter converter = new RabbitJacksonMessageConverter(new JsonMapper());

    private Connection senderConnection;
    private Connection adminConnection;
    private Channel admin;
    private Proxy proxy;
    private String queue;
    private ChannelPool channelPool;
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
        senderConnection = senderFactory.newConnection(SENDER_CONNECTION_NAME);
        messageSender = newMessageSender();
    }

    private ReactiveMessageSender newMessageSender() {
        channelPool = ChannelPoolFactory.createChannelPool(Mono.just(senderConnection),
                new ChannelPoolOptions().maxCacheSize(5));
        Sender sender = new Sender(new SenderOptions().channelPool(channelPool));
        return new ReactiveMessageSender(sender, "TestApp", converter, null, false, new UnroutableMessageNotifier());
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

    /**
     * The outcome counters tell confirmed messages ({@code ack}) apart from no-confirm messages that were only
     * written to the channel ({@code published}), and are counted once per message.
     */
    @Test
    void outcomeMetricsCountEachMessageWithItsOutcome() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Sender sender = new Sender(new SenderOptions().channelPool(ChannelPoolFactory.createChannelPool(
                Mono.just(senderConnection), new ChannelPoolOptions().maxCacheSize(5))));
        try (ReactiveMessageSender metered = new ReactiveMessageSender(sender, "TestApp", converter, null,
                new UnroutableMessageNotifier(), new SenderSettings(false, Duration.ofSeconds(10), 2, 16,
                ReactiveMessageSender.DEFAULT_MAX_MESSAGE_SIZE, new ConnectionBlockedState(), registry, "app"))) {
            for (int i = 0; i < 20; i++) {
                metered.sendWithConfirm("confirmed-" + i, EXCHANGE, ROUTING_KEY, new HashMap<>(), true)
                        .block(Duration.ofSeconds(10));
            }
            for (int i = 0; i < 7; i++) {
                metered.sendNoConfirm("unconfirmed-" + i, EXCHANGE, ROUTING_KEY, new HashMap<>(), true)
                        .block(Duration.ofSeconds(10));
            }

            assertThat(outcome(registry, "ack", true)).isEqualTo(20);
            assertThat(outcome(registry, "published", false)).isEqualTo(7);
            assertThat(registry.find("reactive_commons.publish.outcome").tag("confirm", "false")
                    .tag("outcome", "ack").counter()).as("a no-confirm message is never reported as acked").isNull();
            assertThat(registry.find("reactive_commons.publish.latency")
                    .tags("domain", "app", "confirm", "true", "outcome", "ack").timer().count())
                    .as("one latency per confirmed message").isEqualTo(20);
            assertThat(registry.find("reactive_commons.publish.latency")
                    .tags("domain", "app", "confirm", "false", "outcome", "published").timer().count())
                    .as("one latency per no-confirm message").isEqualTo(7);
            assertThat(registry.find("reactive_commons.publish.message.size")
                    .tags("domain", "app", "confirm", "true").summary().count()).isEqualTo(20);
            assertThat(registry.find("reactive_commons.publish.message.size")
                    .tags("domain", "app", "confirm", "false").summary().count()).isEqualTo(7);
        }
    }

    /**
     * A queue at its length limit with {@code x-overflow: reject-publish} makes the broker answer {@code basic.nack}
     * for a message it received: the caller gets "No ACK when sending message", and both the outcome and the
     * latency are reported as {@code nack}.
     */
    @Test
    void aMessageTheBrokerRejectsIsReportedAsNacked() throws Exception {
        String fullQueue = "tc.full." + UUID.randomUUID();
        String fullRoutingKey = "tc.full.rkey";
        admin.queueDeclare(fullQueue, true, false, false, Map.of("x-max-length", 0, "x-overflow", "reject-publish"));
        admin.queueBind(fullQueue, EXCHANGE, fullRoutingKey);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Sender sender = new Sender(new SenderOptions().channelPool(ChannelPoolFactory.createChannelPool(
                Mono.just(senderConnection), new ChannelPoolOptions().maxCacheSize(5))));
        try (ReactiveMessageSender metered = new ReactiveMessageSender(sender, "TestApp", converter, null,
                new UnroutableMessageNotifier(), new SenderSettings(false, Duration.ofSeconds(10), 2, 16,
                ReactiveMessageSender.DEFAULT_MAX_MESSAGE_SIZE, new ConnectionBlockedState(), registry, "app"))) {
            Mono<Void> send = metered.sendWithConfirm("rejected", EXCHANGE, fullRoutingKey, new HashMap<>(), true);

            assertThatThrownBy(() -> send.block(Duration.ofSeconds(10)))
                    .isExactlyInstanceOf(SendFailureNoAckException.class)
                    .hasMessage("No ACK when sending message");
            assertThat(outcome(registry, "nack", true)).isEqualTo(1);
            assertThat(registry.find("reactive_commons.publish.latency")
                    .tags("domain", "app", "confirm", "true", "outcome", "nack").timer().count()).isEqualTo(1);
        } finally {
            admin.queueDelete(fullQueue);
        }
    }

    /**
     * The socket to the broker stalls WITHOUT a {@code connection.blocked} notification (congested network, a broker
     * that stops reading): the proxy between the sender and the broker is frozen, so the socket buffers fill up and
     * the publishing flows wait inside {@code basicPublish}. The messages waiting to be written are bounded, every
     * caller gets a typed outcome within the send timeout, and a message reported as not published never reaches
     * the broker afterwards.
     */
    @Test
    void aStalledSocketBoundsTheMessagesWaitingToBeWritten() throws Exception {
        Duration sendTimeout = Duration.ofSeconds(3);
        int capacity = 32;
        int messages = 1000;
        Sender sender = new Sender(new SenderOptions().channelPool(ChannelPoolFactory.createChannelPool(
                Mono.just(senderConnection), new ChannelPoolOptions().maxCacheSize(5))));
        try (ReactiveMessageSender stalled = new ReactiveMessageSender(sender, "TestApp", converter, null,
                new UnroutableMessageNotifier(), new SenderSettings(false, sendTimeout, 4, capacity,
                ReactiveMessageSender.DEFAULT_MAX_MESSAGE_SIZE, new ConnectionBlockedState(), null, null))) {
            stalled.sendWithConfirm("warm-up", EXCHANGE, ROUTING_KEY, new HashMap<>(), true)
                    .block(Duration.ofSeconds(10));
            int before = queueSize();
            String blob = "x".repeat(64 * 1024);
            var docker = DockerClientFactory.instance().client();
            List<reactor.util.function.Tuple2<Long, String>> outcomes;
            docker.pauseContainerCmd(TOXIPROXY.getContainerId()).exec();
            try {
                outcomes = Flux.range(0, messages)
                        .flatMap(i -> stalled.sendWithConfirm(blob, EXCHANGE, ROUTING_KEY, new HashMap<>(), true)
                                .thenReturn("ok")
                                .onErrorResume(e -> Mono.just(e.getClass().getSimpleName()))
                                .elapsed(), messages)
                        .collectList()
                        .block(Duration.ofSeconds(60));
            } finally {
                docker.unpauseContainerCmd(TOXIPROXY.getContainerId()).exec();
            }
            // whatever was written while stalled reaches the broker: the queue stops growing
            AtomicInteger lastSize = new AtomicInteger(-1);
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                    .until(() -> {
                        int size = queueSize();
                        return size == lastSize.getAndSet(size);
                    });
            int stored = queueSize() - before;

            Map<String, Long> byOutcome = outcomes.stream()
                    .collect(Collectors.groupingBy(reactor.util.function.Tuple2::getT2, TreeMap::new,
                            Collectors.counting()));
            long slowest = outcomes.stream().mapToLong(reactor.util.function.Tuple2::getT1).max().orElse(0);
            long mayHaveBeenPublished = byOutcome.getOrDefault("ok", 0L)
                    + byOutcome.getOrDefault("SendConfirmUnknownException", 0L);
            System.out.printf("[REAL BROKER] stalled socket: %d sends, slowest %d ms, outcomes %s, stored by the "
                    + "broker afterwards %d (callers told it may have been published: %d)%n",
                    messages, slowest, byOutcome, stored, mayHaveBeenPublished);

            assertThat(outcomes).hasSize(messages);
            assertThat(slowest).as("every send terminates within the send timeout")
                    .isLessThan(sendTimeout.plusSeconds(2).toMillis());
            assertThat(byOutcome).as("a full buffer fails fast").containsKey("SendFailureHandOverRejectedException");
            assertThat((long) stored).as("a caller told 'not published' never sees its message published later")
                    .isLessThanOrEqualTo(mayHaveBeenPublished);
        }
    }

    /**
     * A caller that re-sends a message after an unknown outcome publishes it again: the AMQP message-id is the
     * business id of the message, so the consumer can tell the copies apart from new messages and deduplicate them.
     */
    @Test
    void theMessageIdIsTheBusinessIdOfTheMessage() throws Exception {
        var event = new org.reactivecommons.api.domain.DomainEvent<>("order.created", "event-123", "data");
        var command = new org.reactivecommons.api.domain.Command<>("order.create", "command-456", "data");
        var query = new org.reactivecommons.async.api.AsyncQuery<>("order.find", "data");

        messageSender.sendWithConfirm(event, EXCHANGE, ROUTING_KEY, new HashMap<>(), true).block(Duration.ofSeconds(10));
        messageSender.sendWithConfirm(event, EXCHANGE, ROUTING_KEY, new HashMap<>(), true).block(Duration.ofSeconds(10)); // re-sent
        messageSender.sendWithConfirm(command, EXCHANGE, ROUTING_KEY, new HashMap<>(), true).block(Duration.ofSeconds(10));
        messageSender.sendNoConfirm(query, EXCHANGE, ROUTING_KEY, new HashMap<>(), false).block(Duration.ofSeconds(10));
        awaitQueueSize(4);

        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(admin.basicGet(queue, true).getProps().getMessageId());
        }
        System.out.println("[REAL BROKER] message ids: " + ids);

        assertThat(ids.subList(0, 3)).containsExactly("event-123", "event-123", "command-456");
        assertThat(ids.get(3)).as("a query has no business id: a unique one").isNotBlank()
                .isNotIn("event-123", "command-456");
    }

    private static double outcome(SimpleMeterRegistry registry, String outcome, boolean confirm) {
        Counter counter = registry.find("reactive_commons.publish.outcome").tag("domain", "app")
                .tag("outcome", outcome).tag("confirm", Boolean.toString(confirm)).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void sendsIssuedRightAfterStartUpAreNotLost() throws Exception {
        // No wait after the construction: the flows are still obtaining their channels.
        ReactiveMessageSender justCreated = newMessageSender();
        Counters early = sendMessages(justCreated, 20, EXCHANGE);
        AtomicReference<Throwable> noConfirmError = new AtomicReference<>();
        justCreated.sendNoConfirm("early-no-confirm", EXCHANGE, ROUTING_KEY, new HashMap<>(), true)
                .doOnError(noConfirmError::set)
                .onErrorComplete()
                .block(Duration.ofSeconds(15));
        awaitSettled(early, 20);
        awaitQueueSize(21);

        System.out.printf("[REAL BROKER] right after start-up: sent=21 confirmed=%d failed=%d broker queue=%d%n",
                early.completed.get(), early.failed.get(), queueSize());
        assertThat(early.completed.get()).as("confirmed early messages").isEqualTo(20);
        assertThat(noConfirmError.get()).as("early sendNoConfirm").isNull();
        assertThat(queueSize()).isEqualTo(21);
    }

    @Test
    void sendWithConfirmKeepsConfirmingEveryMessageAfterTheBrokerClosesOneOfTheSenderChannels() throws Exception {
        // 1. Healthy broker: everything is confirmed and routed to the queue.
        Counters healthy = sendMessages(messageSender, 100, EXCHANGE);
        awaitSettled(healthy, 100);
        assertThat(healthy.completed.get()).as("healthy broker: confirmed messages").isEqualTo(100);
        assertThat(queueSize()).isEqualTo(100);
        int channelsBefore = senderChannelsOnTheBroker();

        // 2. Poison message: unknown exchange => broker closes the channel with 404. Only one of the 4
        //    publisher-confirm flows is affected.
        AtomicReference<Throwable> poisonError = new AtomicReference<>();
        long poisonStart = System.nanoTime();
        messageSender.sendWithConfirm("poison", MISSING_EXCHANGE, ROUTING_KEY, new HashMap<>(), true)
                .doOnError(poisonError::set)
                .onErrorComplete()
                .block(Duration.ofSeconds(15));
        Duration poisonDuration = Duration.ofNanos(System.nanoTime() - poisonStart);
        // the flow is re-subscribed with a new channel
        await().atMost(Duration.ofSeconds(10)).until(() -> senderChannelsOnTheBroker() == channelsBefore);

        // 3. Every later message is confirmed.
        int total = 200;
        Counters afterFailure = sendMessages(messageSender, total, EXCHANGE);
        awaitSettled(afterFailure, total);

        System.out.printf("[REAL BROKER] after 404 channel close: poison=%s (in %d ms) | sent=%d completed=%d "
                        + "failed=%d hanging=%d%n", poisonError.get(), poisonDuration.toMillis(), total,
                afterFailure.completed.get(), afterFailure.failed.get(), afterFailure.hanging(total));

        assertThat(poisonError.get()).as("the poison message was written, then its channel was closed: in doubt")
                .isInstanceOf(SendConfirmUnknownException.class);
        assertThat(poisonDuration).as("the poison message finishes right away").isLessThan(Duration.ofSeconds(5));
        assertThat(afterFailure.completed.get()).as("confirmed messages").isEqualTo(total);
        assertThat(afterFailure.failed.get()).isZero();
        assertThat(afterFailure.hanging(total)).as("messages never resolved").isZero();
        assertThat(queueSize()).as("every confirmed message reached the broker").isEqualTo(100 + total);
    }

    @Test
    void sendNoConfirmReportsTheFailureAndKeepsPublishingAfterTheBrokerClosesItsChannel() throws Exception {
        // 1. Healthy broker.
        for (int i = 0; i < 5; i++) {
            messageSender.sendNoConfirm("ok-" + i, EXCHANGE, ROUTING_KEY, new HashMap<>(), true)
                    .block(Duration.ofSeconds(15));
        }
        awaitQueueSize(5);
        assertThat(queueSize()).isEqualTo(5);

        // 2. Poison message: written to the channel (so reported as sent), then the broker closes the channel.
        int channelsBefore = senderChannelsOnTheBroker();
        messageSender.sendNoConfirm("poison", MISSING_EXCHANGE, ROUTING_KEY, new HashMap<>(), true)
                .block(Duration.ofSeconds(15));
        await().atMost(Duration.ofSeconds(10)).until(() -> senderChannelsOnTheBroker() < channelsBefore);

        // 3. The next publish hits the closed channel: it is reported as failed, without blocking the caller,
        //    and the flow is re-subscribed with a new channel.
        AtomicReference<Throwable> afterFailureError = new AtomicReference<>();
        long start = System.nanoTime();
        messageSender.sendNoConfirm("after-failure", EXCHANGE, ROUTING_KEY, new HashMap<>(), true)
                .doOnError(afterFailureError::set)
                .onErrorComplete()
                .block(Duration.ofSeconds(15));
        Duration blocked = Duration.ofNanos(System.nanoTime() - start);

        // 4. Later messages reach the broker, and each call reports success only once published.
        int later = 20;
        List<Throwable> laterErrors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < later; i++) {
            messageSender.sendNoConfirm("later-" + i, EXCHANGE, ROUTING_KEY, new HashMap<>(), true)
                    .doOnError(laterErrors::add)
                    .onErrorComplete()
                    .block(Duration.ofSeconds(15));
        }
        awaitQueueSize(5 + later);

        System.out.printf("[REAL BROKER] sendNoConfirm after the channel failure: first=%s (caller waited %d ms) | "
                        + "later sends=%d errors=%d reached the broker=%d%n", afterFailureError.get(),
                blocked.toMillis(), later, laterErrors.size(), queueSize() - 5);
        assertThat(blocked).as("the caller is not blocked by a retry loop").isLessThan(Duration.ofSeconds(1));
        assertThat(afterFailureError.get()).as("a message that could not be published is reported as failed")
                .isInstanceOf(SendFailureNoAckException.class);
        assertThat(laterErrors).isEmpty();
        assertThat(queueSize()).as("every later message reached the broker").isEqualTo(5 + later);
    }

    /**
     * Messages that were published but not yet confirmed when the whole CONNECTION is lost.
     *
     * <p>Toxiproxy silences the broker-to-client direction (so publisher confirms never arrive) while the
     * messages still reach the broker. Then the connection is cut and restored, and the client's automatic
     * recovery brings the connection and channels back.
     */
    @Test
    void sendWithConfirmInFlightDuringConnectionLossFailsWithUnknownOutcomeAndConfirmsAreNotCrossed()
            throws Exception {
        // 1. Healthy: 20 confirmed messages.
        Counters healthy = sendMessages(messageSender, 20, EXCHANGE);
        awaitSettled(healthy, 20);
        assertThat(healthy.completed.get()).isEqualTo(20);
        assertThat(queueSize()).isEqualTo(20);
        int channelsBefore = senderChannelsOnTheBroker();

        // 2. The broker goes silent towards the client: publishes reach it, confirms do not come back.
        Toxic silence = proxy.toxics().timeout("silence-broker", ToxicDirection.DOWNSTREAM, 0);
        int inFlight = 50;
        List<Throwable> inDoubtErrors = new CopyOnWriteArrayList<>();
        Counters inDoubt = sendMessages(messageSender, inFlight, EXCHANGE, inDoubtErrors);
        await().during(Duration.ofMillis(1_500)).atMost(Duration.ofSeconds(3))
                .until(() -> inDoubt.completed.get() == 0);
        System.out.printf("[REAL BROKER] in flight: sent=%d, confirmed to caller=%d, already stored by broker=%d%n",
                inFlight, inDoubt.completed.get(), queueSize() - 20);
        assertThat(inDoubt.completed.get()).as("no confirm can arrive while the broker is silent").isZero();

        // 3. Connection loss: the network is cut, the sender sees the connection die, then the network is restored.
        proxy.disable();
        await().atMost(Duration.ofSeconds(10)).until(() -> !senderConnection.isOpen());
        silence.remove();
        proxy.enable();
        awaitConnectionRecovered();
        awaitSettled(inDoubt, inFlight);

        int storedByBroker = queueSize() - 20;
        System.out.printf("[REAL BROKER] connection recovered. In-flight messages: completed=%d failed=%d "
                        + "pending=%d (broker stored %d of them)%n",
                inDoubt.completed.get(), inDoubt.failed.get(), inDoubt.hanging(inFlight), storedByBroker);

        // The in-flight messages are in doubt: the broker has them, but the client cannot know it. The callers are
        // told so (at-least-once: they decide whether to re-send).
        assertThat(storedByBroker).as("the broker stored every in-flight message").isEqualTo(inFlight);
        assertThat(inDoubt.failed.get()).as("in-flight messages are reported").isEqualTo(inFlight);
        assertThat(inDoubtErrors).allSatisfy(e -> assertThat(e).isInstanceOf(SendConfirmUnknownException.class));

        // 4. After the recovery the sender is used normally.
        int afterTotal = 100;
        Counters afterRecovery = sendMessages(messageSender, afterTotal, EXCHANGE);
        awaitSettled(afterRecovery, afterTotal);
        await().atMost(Duration.ofSeconds(10)).until(() -> queueSize() == 20 + inFlight + afterTotal
                && senderChannelsOnTheBroker() == channelsBefore);

        int brokerQueue = queueSize();
        int channelsAfter = senderChannelsOnTheBroker();
        System.out.printf("[REAL BROKER] after recovery: new sends=%d completed=%d failed=%d hanging=%d | "
                        + "old in-flight messages: completed=%d failed=%d | broker queue=%d | "
                        + "sender channels before=%d after=%d%n",
                afterTotal, afterRecovery.completed.get(), afterRecovery.failed.get(),
                afterRecovery.hanging(afterTotal), inDoubt.completed.get(), inDoubt.failed.get(), brokerQueue,
                channelsBefore, channelsAfter);

        assertThat(afterRecovery.completed.get()).as("every new message is confirmed").isEqualTo(afterTotal);
        assertThat(afterRecovery.hanging(afterTotal)).isZero();
        assertThat(inDoubt.completed.get())
                .as("old in-flight messages never receive the confirms of the new ones").isZero();
        assertThat(brokerQueue).isEqualTo(20 + inFlight + afterTotal);
        assertThat(channelsAfter).as("no orphan channels after the recovery").isEqualTo(channelsBefore);
    }

    private void awaitConnectionRecovered() {
        await().alias("client automatic recovery restored the connection").atMost(Duration.ofSeconds(30))
                .until(senderConnection::isOpen);
    }

    /**
     * Channels open on the broker for the sender connection, as reported by the broker itself.
     */
    private int senderChannelsOnTheBroker() {
        return await().alias("sender connection listed by the broker").atMost(Duration.ofSeconds(10))
                .until(this::senderChannelsListed, Optional::isPresent).orElseThrow();
    }

    private Optional<Integer> senderChannelsListed() throws Exception {
        // client_properties holds the client-provided connection name; columns are tab separated
        ExecResult result = RABBIT.execInContainer("rabbitmqctl", "-q", "list_connections", "client_properties",
                "channels");
        for (String line : result.getStdout().split("\n")) {
            String[] columns = line.trim().split("\t");
            if (columns.length >= 2 && line.contains("\"" + SENDER_CONNECTION_NAME + "\"")) {
                return Optional.of(Integer.parseInt(columns[columns.length - 1].trim()));
            }
        }
        return Optional.empty();
    }

    private record Counters(AtomicInteger completed, AtomicInteger failed) {
        int hanging(int total) {
            return total - completed.get() - failed.get();
        }
    }

    private Counters sendMessages(ReactiveMessageSender sender, int total, String exchange) {
        return sendMessages(sender, total, exchange, new CopyOnWriteArrayList<>());
    }

    private Counters sendMessages(ReactiveMessageSender sender, int total, String exchange, List<Throwable> errors) {
        Counters counters = new Counters(new AtomicInteger(), new AtomicInteger());
        for (int i = 0; i < total; i++) {
            sender.sendWithConfirm("payload-" + i, exchange, ROUTING_KEY, new HashMap<>(), true)
                    .subscribe(v -> {
                    }, e -> {
                        errors.add(e);
                        counters.failed.incrementAndGet();
                    }, counters.completed::incrementAndGet);
        }
        return counters;
    }

    private void awaitSettled(Counters counters, int total) {
        await().atMost(Duration.ofSeconds(15)).until(() -> counters.hanging(total) <= 0);
    }

    private void awaitQueueSize(int expected) {
        await().atMost(Duration.ofSeconds(5)).until(() -> queueSize() >= expected);
    }

    private int queueSize() throws Exception {
        return admin.queueDeclarePassive(queue).getMessageCount();
    }
}
