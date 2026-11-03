package org.reactivecommons.async.rabbit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.exceptions.SendConfirmUnknownException;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.starter.config.ConnectionManager;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * An application whose user may not declare exchanges publishes events, on a REAL RabbitMQ broker, to an exchange that
 * does not exist: the declaration fails with {@code 403 ACCESS_REFUSED} and every publish with {@code 404 NOT_FOUND}.
 * The failed declaration is logged and the health check reports the publishing flows down with the reason of the
 * broker. A publish fails as in doubt: the broker closes the channel, so every message waiting for its confirmation
 * on it is in doubt, as the broker does not say which one closed it.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ExchangeDeclarationFailureRabbitContainerTest {

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Test
    void anExchangeThatCouldNotBeDeclaredIsLoggedAndReportedByTheHealthCheck() throws Exception {
        rabbitmqctl("add_user", "limited", "limited");
        // no configure permission: it cannot declare exchanges or queues
        rabbitmqctl("set_permissions", "-p", "/", "limited", "^$", ".*", ".*");
        String exchange = "edf.events." + UUID.randomUUID();
        List<String> warnings = new CopyOnWriteArrayList<>();
        Logger logger = (Logger) LoggerFactory.getLogger(ReactiveMessageSender.class);
        AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getLevel() == Level.WARN) {
                    warnings.add(event.getFormattedMessage());
                }
            }
        };
        capture.start();
        logger.addAppender(capture);
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "edf",
                false, false, Map.of("spring.rabbitmq.username", "limited", "spring.rabbitmq.password", "limited",
                        "app.async.app.domain.events.exchange", exchange, "app.async.app.sendTimeout", "2s"),
                RabbitScenarioSupport.Handlers.class, SendEvents.class)) {
            DomainEventBus bus = context.getBean(DomainEventBus.class);
            RabbitMQBrokerProvider provider = (RabbitMQBrokerProvider) context.getBean(ConnectionManager.class)
                    .getProviders().get(RabbitScenarioSupport.DEFAULT_DOMAIN);

            // every publish fails, and the health reports the flows down after 3 re-subscriptions without a
            // confirmed message (the flows re-subscribe with a backoff)
            AtomicInteger published = new AtomicInteger();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(1_500)).until(() -> {
                int i = published.incrementAndGet();
                Throwable root = rootCause(Mono.from(bus.emit(new DomainEvent<>("scenario.event", "e" + i, Map.of())))
                        .then(Mono.<Throwable>empty())
                        .onErrorResume(Mono::just)
                        .block(Duration.ofSeconds(10)));
                assertThat(root).as("publish %d", i).isInstanceOf(SendConfirmUnknownException.class);
                RCHealth current = provider.healthCheck().block(Duration.ofSeconds(5));
                return current != null && current.status() == RCHealth.Status.DOWN;
            });
            RCHealth health = provider.healthCheck().block(Duration.ofSeconds(5));

            System.out.printf("[REAL BROKER] declaration warnings=%s, health=%s%n", warnings, health);
            assertThat(warnings).as("failed declaration logged")
                    .anySatisfy(warning -> assertThat(warning).contains(exchange, "could not be declared",
                            "ACCESS_REFUSED"));
            assertThat(health).isNotNull();
            assertThat(health.status()).isEqualTo(RCHealth.Status.DOWN);
            assertThat(String.valueOf(health.details().get("error"))).as("reason in the health check")
                    .contains("publishing flows keep failing", "NOT_FOUND - no exchange '" + exchange + "'");
        } finally {
            logger.detachAppender(capture);
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    private static void rabbitmqctl(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "rabbitmqctl";
        System.arraycopy(args, 0, command, 1, args.length);
        ExecResult result = RABBIT.execInContainer(command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
}
