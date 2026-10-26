package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;

/**
 * Sends batches of commands with {@code RabbitDirectAsyncGateway.sendCommands} to a REAL RabbitMQ broker.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class CommandBatchRabbitContainerTest {

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    /**
     * Every message of the batch is confirmed right away, but the batch as a whole takes longer than the send timeout
     * because its commands are produced slowly.
     */
    @Test
    void aHealthyBatchLongerThanTheSendTimeoutIsPublished() throws Exception {
        String target = "cb.slow." + UUID.randomUUID();
        try (AnnotationConfigApplicationContext context = start("cbs");
             Connection connection = connect()) {
            String queue = bindQueue(connection, target, Map.of());
            AtomicInteger produced = new AtomicInteger();
            Flux<Command<Map<String, Integer>>> commands = Flux.range(0, 10)
                    .delayElements(Duration.ofMillis(300))
                    .doOnNext(i -> produced.incrementAndGet())
                    .map(i -> new Command<>("scenario.command", "cmd-" + i, Map.of("i", i)));

            long start = System.currentTimeMillis();
            Throwable error = outcome(gateway(context).sendCommands(commands, target).then());
            long elapsed = System.currentTimeMillis() - start;

            long delivered = messagesIn(connection, queue, 10);
            System.out.printf("[REAL BROKER] slow healthy batch (send timeout 1s): error=%s after %d ms, "
                    + "produced=%d, delivered=%d%n", error, elapsed, produced.get(), delivered);
            assertThat(error).as("outcome of a healthy batch longer than the send timeout").isNull();
            assertThat(delivered).as("messages delivered").isEqualTo(10);
        }
    }

    /**
     * The queue only accepts 3 messages ({@code x-overflow: reject-publish}): the broker nacks the other 7.
     */
    @Test
    void aBatchWithRejectedMessagesReportsWhichOnesFailed() throws Exception {
        String target = "cb.rejected." + UUID.randomUUID();
        try (AnnotationConfigApplicationContext context = start("cbr");
             Connection connection = connect()) {
            String queue = bindQueue(connection, target, Map.of("x-max-length", 3, "x-overflow", "reject-publish"));
            AtomicInteger produced = new AtomicInteger();
            Flux<Command<Map<String, Integer>>> commands = Flux.range(0, 10)
                    .doOnNext(i -> produced.incrementAndGet())
                    .map(i -> new Command<>("scenario.command", "cmd-" + i, Map.of("i", i)));

            Throwable error = outcome(gateway(context).sendCommands(commands, target).then());

            long delivered = messagesIn(connection, queue, 3);
            System.out.printf("[REAL BROKER] batch with rejected messages: produced=%d, delivered=%d, error=%s%n",
                    produced.get(), delivered, error);
            assertThat(error).as("outcome of a batch with rejected messages")
                    .isInstanceOf(SendFailureNoAckException.class);
            assertThat(produced.get()).as("commands taken from the batch").isEqualTo(10);
            assertThat(delivered).as("messages delivered").isEqualTo(3);
            assertThat(error.getMessage()).as("failure of the batch")
                    .contains("7 of the 10 messages")
                    .contains("cmd-3", "cmd-9")
                    .doesNotContain("cmd-0", "cmd-2");
        }
    }

    /**
     * A memory alarm is raised in the middle of the batch: the broker stops reading from the connection, so the
     * messages published from then on are never confirmed and the batch fails within the send timeout.
     */
    @Test
    void aBatchFailsWhenItsMessagesAreNotConfirmedWithinTheSendTimeout() throws Exception {
        String target = "cb.alarm." + UUID.randomUUID();
        try (AnnotationConfigApplicationContext context = start("cba");
             Connection connection = connect()) {
            bindQueue(connection, target, Map.of());
            AtomicLong lastProducedAt = new AtomicLong();
            Flux<Command<Map<String, Integer>>> commands = Flux.range(0, 6)
                    .delayElements(Duration.ofMillis(300))
                    .doOnNext(i -> lastProducedAt.set(System.currentTimeMillis()))
                    .map(i -> new Command<>("scenario.command", "cmd-" + i, Map.of("i", i)));

            var send = gateway(context).sendCommands(commands, target).then().toFuture();
            Thread.sleep(800); // cmd-0 and cmd-1 are confirmed before the alarm
            Throwable error;
            long sinceLastMessage;
            try {
                rabbitmqctl("set_vm_memory_high_watermark", "0");
                error = outcome(Mono.fromFuture(send));
                sinceLastMessage = System.currentTimeMillis() - lastProducedAt.get();
            } finally {
                rabbitmqctl("set_vm_memory_high_watermark", "0.4");
            }

            System.out.printf("[REAL BROKER] batch during a memory alarm (send timeout 1s): error=%s %d ms after its "
                    + "last message%n", error, sinceLastMessage);
            assertThat(error).as("outcome of a batch whose messages are not confirmed")
                    .isInstanceOf(SendConfirmUnknownException.class)
                    .hasMessageContaining("(in doubt)")
                    .hasMessageNotContaining("cmd-0");
            assertThat(sinceLastMessage).as("time from the last message until the batch fails")
                    .isBetween(1_000L, 2_000L);
        }
    }

    private static void rabbitmqctl(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "rabbitmqctl";
        System.arraycopy(args, 0, command, 1, args.length);
        assertThat(RABBIT.execInContainer(command).getExitCode()).isZero();
    }

    private static AnnotationConfigApplicationContext start(String prefix) {
        return RabbitScenarioSupport.startWithHandlers(RABBIT, prefix, false, false,
                Map.of("app.async.app.sendTimeout", "1s"), RabbitScenarioSupport.Handlers.class, SendDirect.class);
    }

    private static RabbitDirectAsyncGateway gateway(AnnotationConfigApplicationContext context) {
        return (RabbitDirectAsyncGateway) context.getBean(ConnectionManager.class).getProviders()
                .get(RabbitScenarioSupport.DEFAULT_DOMAIN).getDirectAsyncGateway();
    }

    private static Throwable outcome(Mono<Void> send) {
        return send.timeout(Duration.ofSeconds(30))
                .flatMap(ignored -> Mono.<Throwable>empty())
                .onErrorResume(Mono::just)
                .block();
    }

    private static Connection connect() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        return factory.newConnection();
    }

    private static String bindQueue(Connection connection, String routingKey, Map<String, Object> arguments)
            throws Exception {
        try (Channel channel = connection.createChannel()) {
            String queue = channel.queueDeclare(routingKey, true, false, false, arguments).getQueue();
            channel.exchangeDeclare("directMessages", "direct", true);
            channel.queueBind(queue, "directMessages", routingKey);
            return queue;
        }
    }

    /**
     * The messages in the queue once there are at least {@code expected} or 5 seconds have passed.
     */
    private static long messagesIn(Connection connection, String queue, long expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        try (Channel channel = connection.createChannel()) {
            long count = channel.queueDeclarePassive(queue).getMessageCount();
            while (count < expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
                count = channel.queueDeclarePassive(queue).getMessageCount();
            }
            return count;
        }
    }
}
