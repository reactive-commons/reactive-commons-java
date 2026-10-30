package org.reactivecommons.async.rabbit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;

/**
 * Starts an application whose connections a REAL RabbitMQ broker refuses (wrong credentials): the connections are
 * retried every few seconds for as long as it lasts, and logging each failed attempt as an error with its stack trace
 * floods the log of the application (and its alerts) during the whole outage.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConnectionRetryLoggingRabbitContainerTest {

    private static final Duration OUTAGE = Duration.ofSeconds(12);

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Test
    void theFailedAttemptsToConnectDoNotFloodTheLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(RabbitMQSetupUtils.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "crl",
                false, false, Map.of("spring.rabbitmq.password", "wrong-password"),
                RabbitScenarioSupport.Handlers.class, SendDirect.class, ListenCommands.class)) {
            // the publishing and the consuming connections keep failing meanwhile
            await().pollDelay(OUTAGE).atMost(OUTAGE.plusSeconds(1)).until(() -> true);
        } finally {
            logger.detachAppender(logged);
        }

        List<ILoggingEvent> failures = logged.list.stream()
                .filter(event -> event.getFormattedMessage().contains("connection to RabbitMQ"))
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .toList();
        long withStackTrace = failures.stream().filter(event -> event.getThrowableProxy() != null).count();
        System.out.printf("[REAL BROKER] %d s refusing the connections: %d warnings or errors logged about them, %d "
                + "with a stack trace%n", OUTAGE.toSeconds(), failures.size(), withStackTrace);
        // the publishing and the consuming connections: the first failure of each one, with its stack trace
        assertThat(withStackTrace).as("failed attempts logged with a stack trace").isLessThanOrEqualTo(2);
        assertThat(failures).as("failed attempts logged as warnings or errors").hasSizeLessThanOrEqualTo(2);
    }
}
