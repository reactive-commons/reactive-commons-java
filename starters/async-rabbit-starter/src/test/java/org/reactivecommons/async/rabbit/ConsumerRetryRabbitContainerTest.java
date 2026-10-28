package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConnectionFactory;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.rabbit.communications.ConnectionBlockedState;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * How a listener handles messages it cannot process, against a REAL broker: messages without a handler, discard
 * notifications that cannot be published, and queries whose reply cannot be published or whose caller gave up.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConsumerRetryRabbitContainerTest {

    private static final String EVENTS_EXCHANGE = "domainEvents";
    private static final AtomicInteger executions = new AtomicInteger();
    private static volatile CompletableFuture<Void> release = new CompletableFuture<>();

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @AfterEach
    void cleanUp() throws Exception {
        RABBIT.execInContainer("rabbitmqctl", "set_vm_memory_high_watermark", "0.4");
        release.complete(null);
    }

    /**
     * A binding left by a previous version routes an event that no handler serves anymore: retrying it can never
     * succeed, so it is discarded (and its discard notified) instead of being requeued forever.
     */
    @Test
    void anEventWithoutHandlerIsDiscardedInsteadOfRetriedForever() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "stale",
                false, false, OkHandlers.class, SendEvents.class, ListenEvents.class);
             com.rabbitmq.client.Connection admin = adminConnection()) {
            String queue = "stale-app.subsEvents";
            RabbitScenarioSupport.awaitConsumers(RABBIT, queue);
            Channel channel = admin.createChannel();
            channel.queueBind(queue, EVENTS_EXCHANGE, "stale.event"); // the binding of a removed handler
            String probe = probeQueue(channel, "stale.event.dlq");

            emit(context, "stale.event");

            boolean discarded = eventually(() -> messages(queue) == 0 && messageCount(channel, probe) == 1);
            System.out.println("[REAL BROKER] event without handler: queue=" + messages(queue) + " dlq notifications="
                    + messageCount(channel, probe));
            assertThat(discarded).as("discarded and notified, not requeued forever").isTrue();
        }
    }

    @Test
    void aCommandWithoutHandlerIsDiscardedInsteadOfRetriedForever() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "nocmd",
                false, false, OkHandlers.class, SendDirect.class, ListenCommands.class);
             com.rabbitmq.client.Connection admin = adminConnection()) {
            String queue = "nocmd-app";
            RabbitScenarioSupport.awaitConsumers(RABBIT, queue);
            Channel channel = admin.createChannel();
            String probe = probeQueue(channel, "unknown.command.dlq");

            context.getBean(DirectAsyncGateway.class).sendCommand(new Command<>("unknown.command",
                    UUID.randomUUID().toString(), Map.of()), queue).block(Duration.ofSeconds(10));

            boolean discarded = eventually(() -> messages(queue) == 0 && messageCount(channel, probe) == 1);
            System.out.println("[REAL BROKER] command without handler: queue=" + messages(queue)
                    + " dlq notifications=" + messageCount(channel, probe));
            assertThat(discarded).as("discarded and notified, not requeued forever").isTrue();
        }
    }

    /**
     * The handler fails for good, but its discard notification cannot be published (here its exchange is gone):
     * the message must stay in the queue until the discard is notified, not be acknowledged and lost.
     */
    @Test
    void aDiscardedMessageIsNotLostWhenItsDiscardNotificationFails() throws Exception {
        release = new CompletableFuture<>();
        executions.set(0);
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "disc",
                false, false, FailingEventHandlers.class, SendEvents.class, ListenEvents.class, NoRetries.class);
             com.rabbitmq.client.Connection admin = adminConnection()) {
            String queue = "disc-app.subsEvents";
            RabbitScenarioSupport.awaitConsumers(RABBIT, queue);
            Channel channel = admin.createChannel();
            emit(context, "failing.event");
            assertThat(eventually(() -> executions.get() == 1)).isTrue();

            channel.exchangeDelete(EVENTS_EXCHANGE); // the discard notification can no longer be published
            release.complete(null);
            await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(6)).until(() -> messages(queue) == 1);
            int whileUnnotified = messages(queue);

            channel.exchangeDeclare(EVENTS_EXCHANGE, "topic", true);
            String probe = probeQueue(channel, "failing.event.dlq");
            boolean notified = eventually(() -> messages(queue) == 0 && messageCount(channel, probe) >= 1);
            System.out.println("[REAL BROKER] discard notification failing: message kept in the queue="
                    + whileUnnotified + ", then notified=" + notified + " after " + executions.get() + " executions");

            assertThat(whileUnnotified).as("not acknowledged while its discard is not notified").isEqualTo(1);
            assertThat(notified).as("discarded once the notification can be published").isTrue();
        }
    }

    /**
     * With mandatory publishing, the broker returns a discard notification that no queue is bound to (nobody listens
     * to the .dlq events, the usual case). It was published all the same: the discarded message is acknowledged
     * instead of being requeued, and its handler run again, forever.
     */
    @Test
    void aDiscardNobodyListensToIsAcknowledgedWhenPublishingIsMandatory() {
        release = CompletableFuture.completedFuture(null);
        executions.set(0);
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "mand",
                false, false, Map.of("app.async.app.mandatory", "true"), FailingEventHandlers.class,
                SendEvents.class, ListenEvents.class, NoRetries.class)) {
            String queue = "mand-app.subsEvents";
            RabbitScenarioSupport.awaitConsumers(RABBIT, queue);

            emit(context, "failing.event");

            boolean acknowledged = eventually(() -> executions.get() >= 1 && messages(queue) == 0);
            // a requeued message would be handled again (retryDelay is 1 s)
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> executions.get() <= 1);
            System.out.println("[REAL BROKER] discard notification returned: queue=" + messages(queue)
                    + " executions=" + executions.get());

            assertThat(acknowledged).as("discarded and acknowledged, not requeued").isTrue();
            assertThat(executions.get()).as("the handler is not run again").isEqualTo(1);
        }
    }

    /**
     * The query was handled, but its reply cannot be published (the broker blocks the publishing connection):
     * running the query again cannot help its caller, it only repeats the work.
     */
    @Test
    void aQueryWhoseReplyCannotBePublishedIsNotExecutedAgain() throws Exception {
        release = new CompletableFuture<>();
        executions.set(0);
        try (AnnotationConfigApplicationContext server = RabbitScenarioSupport.startWithHandlers(RABBIT, "rsrv",
                false, false, HeldQueryHandlers.class, ListenQueries.class, SendEvents.class);
             AnnotationConfigApplicationContext requester = RabbitScenarioSupport.startWithHandlers(RABBIT, "rreq",
                     false, true, OkHandlers.class, SendDirect.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "rsrv-app.query");
            requester.getBean(DirectAsyncGateway.class).requestReply(new AsyncQuery<>("held.query", "q"),
                    "rsrv-app", String.class).subscribe(r -> { }, e -> { });
            assertThat(eventually(() -> executions.get() == 1)).isTrue();

            RABBIT.execInContainer("rabbitmqctl", "set_vm_memory_high_watermark", "0.0000001");
            ConnectionBlockedState state = ((RabbitMQBrokerProvider) server.getBean(ConnectionManager.class)
                    .getProviders().get(RabbitScenarioSupport.DEFAULT_DOMAIN)).sender().blockedState();
            catchThrowable(() -> emit(server, "any.event")); // makes the broker block the publishing connection
            assertThat(eventually(state::isBlocked)).isTrue();
            release.complete(null); // the reply is published now, while blocked: it fails
            await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(7)).until(() -> executions.get() == 1);
            System.out.println("[REAL BROKER] query whose reply could not be published: executions="
                    + executions.get());

            assertThat(executions.get()).as("handled once, not re-executed").isEqualTo(1);
        }
    }

    /**
     * A query that keeps failing is useless once its caller stopped waiting for the reply: it is not retried
     * past the reply timeout.
     */
    @Test
    void aFailingQueryIsNotRetriedOnceItsCallerTimedOut() {
        executions.set(0);
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "qexp",
                false, true, FailingQueryHandlers.class, SendDirect.class, ListenQueries.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "qexp-app.query");
            Throwable callerOutcome = catchThrowable(() -> context.getBean(DirectAsyncGateway.class)
                    .requestReply(new AsyncQuery<>("failing.query", "q"), "qexp-app", String.class)
                    .block(Duration.ofSeconds(10)));

            // the executions stop once the caller timed out (reply timeout 2 s, retries every 1 s)
            AtomicInteger seen = new AtomicInteger(-1);
            await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(20)).until(() -> {
                int executed = executions.get();
                return executed == seen.getAndSet(executed);
            });
            int afterTimeout = executions.get();
            System.out.println("[REAL BROKER] failing query: caller got " + callerOutcome + "; executions "
                    + afterTimeout + " -> " + executions.get() + ", left in the queue " + messages("qexp-app.query"));

            assertThat(executions.get()).as("no retry once the caller timed out").isEqualTo(afterTimeout);
            assertThat(messages("qexp-app.query")).isZero();
        }
    }

    /**
     * With {@code discardTimeoutQueries}, a query still running when its caller stops waiting is discarded: it must be
     * acknowledged, not left in the queue to be retried over and over.
     */
    @Test
    void aQueryDiscardedByTimeoutIsAcknowledged() {
        executions.set(0);
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "qdis",
                false, true, SlowQueryHandlers.class, SendDirect.class, ListenQueries.class,
                DiscardTimeoutQueries.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, "qdis-app.query");
            catchThrowable(() -> context.getBean(DirectAsyncGateway.class)
                    .requestReply(new AsyncQuery<>("slow.query", "q"), "qdis-app", String.class)
                    .block(Duration.ofSeconds(10)));

            boolean acknowledged = eventually(() -> messages("qdis-app.query") == 0);
            await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> executions.get() <= 1);
            System.out.println("[REAL BROKER] query discarded by timeout: acknowledged=" + acknowledged
                    + ", left in the queue " + messages("qdis-app.query") + ", executions " + executions.get());

            assertThat(acknowledged).isTrue();
            assertThat(messages("qdis-app.query")).isZero();
            assertThat(executions.get()).as("not executed again").isEqualTo(1);
        }
    }

    /**
     * A query published without the headers to reply to it (e.g. by another client): it can never be answered, so it
     * is discarded without running its handler, instead of failing after running it and being retried forever.
     */
    @Test
    void aQueryWithoutReplyHeadersIsDiscardedWithoutRunningIt() throws Exception {
        release = new CompletableFuture<>();
        release.complete(null);
        executions.set(0);
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "qmal",
                false, false, HeldQueryHandlers.class, ListenQueries.class);
             com.rabbitmq.client.Connection admin = adminConnection()) {
            String queue = "qmal-app.query";
            RabbitScenarioSupport.awaitConsumers(RABBIT, queue);
            Channel channel = admin.createChannel();
            channel.exchangeDeclare(EVENTS_EXCHANGE, "topic", true); // this application does not emit events
            String probe = probeQueue(channel, "held.query.dlq");

            channel.basicPublish("directMessages", queue, new com.rabbitmq.client.AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .headers(Map.of("x-serveQuery-id", "held.query")) // no reply-id nor correlation-id
                    .build(), "{\"resource\":\"held.query\",\"queryData\":\"q\"}".getBytes());

            boolean discarded = eventually(() -> messages(queue) == 0 && messageCount(channel, probe) == 1);
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).until(() -> executions.get() == 0);
            System.out.println("[REAL BROKER] query without reply headers: discarded=" + discarded
                    + ", executions=" + executions.get() + ", left in the queue " + messages(queue));

            assertThat(discarded).as("discarded and notified").isTrue();
            assertThat(executions.get()).as("its handler is not run").isZero();
        }
    }

    private static void emit(AnnotationConfigApplicationContext context, String name) {
        Mono.from(context.getBean(DomainEventBus.class).emit(new DomainEvent<>(name, UUID.randomUUID().toString(),
                Map.of("k", "v")))).block(Duration.ofSeconds(10));
    }

    /**
     * Messages of a queue, ready and unacknowledged.
     */
    private static int messages(String queue) {
        try {
            ExecResult result = RABBIT.execInContainer("rabbitmqctl", "-q", "list_queues", "name", "messages");
            return result.getStdout().lines().map(line -> line.trim().split("\\s+"))
                    .filter(c -> c.length == 2 && c[0].equals(queue))
                    .mapToInt(c -> Integer.parseInt(c[1])).findFirst().orElse(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int messageCount(Channel channel, String queue) {
        try {
            return channel.queueDeclarePassive(queue).getMessageCount();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * A queue that receives the discard notifications ({@code <name>.dlq} events) of the given routing key.
     */
    private static String probeQueue(Channel channel, String routingKey) throws Exception {
        String queue = channel.queueDeclare().getQueue();
        channel.queueBind(queue, EVENTS_EXCHANGE, routingKey);
        return queue;
    }

    private static com.rabbitmq.client.Connection adminConnection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        return factory.newConnection("retry-admin");
    }

    /**
     * Whether the condition holds within 20 seconds.
     */
    private static boolean eventually(BooleanSupplier condition) {
        try {
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).until(condition::getAsBoolean);
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }

    @Configuration
    static class OkHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("scenario.event", event -> Mono.empty(), Map.class)
                    .handleCommand("scenario.command", command -> Mono.empty(), Map.class);
        }
    }

    @Configuration
    static class FailingEventHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("failing.event", event -> Mono.fromRunnable(executions::incrementAndGet)
                            .then(Mono.fromFuture(release))
                            .then(Mono.error(new IllegalStateException("handler failure"))), Map.class);
        }
    }

    @Configuration
    static class HeldQueryHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .serveQuery("held.query", query -> Mono.fromRunnable(executions::incrementAndGet)
                            .then(Mono.fromFuture(release)).thenReturn("ok"), String.class);
        }
    }

    @Configuration
    static class FailingQueryHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .serveQuery("failing.query", query -> Mono.fromRunnable(executions::incrementAndGet)
                            .then(Mono.error(new IllegalStateException("query failure"))), String.class);
        }

        @Bean
        BrokerConfig brokerConfig() {
            return new BrokerConfig(false, true, true, Duration.ofSeconds(2));
        }
    }

    @Configuration
    static class SlowQueryHandlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .serveQuery("slow.query", query -> Mono.fromRunnable(executions::incrementAndGet)
                            .then(Mono.delay(Duration.ofSeconds(4))).thenReturn("late"), String.class);
        }

        @Bean
        BrokerConfig brokerConfig() {
            return new BrokerConfig(false, true, true, Duration.ofSeconds(2));
        }
    }

    @Configuration
    static class DiscardTimeoutQueries {
        @Bean
        AsyncPropsDomain.RabbitPropsCustomizer discardTimeoutQueriesCustomizer() {
            return domainProperties -> domainProperties.customize(RabbitScenarioSupport.DEFAULT_DOMAIN, props ->
                    props.getDirect().setDiscardTimeoutQueries(true));
        }
    }

    @Configuration
    static class NoRetries {
        @Bean
        AsyncPropsDomain.RabbitPropsCustomizer noRetriesCustomizer() {
            return domainProperties -> domainProperties.customize(RabbitScenarioSupport.DEFAULT_DOMAIN, props ->
                    props.setMaxRetries(0));
        }
    }
}
