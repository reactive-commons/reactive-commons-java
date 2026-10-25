package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;

/**
 * Serves queries with {@code discardTimeoutQueries: true} on a REAL RabbitMQ broker: a query whose handler answers well
 * within the reply timeout of its caller must be answered, never discarded as timed out.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class QueryReplyTimeoutRabbitContainerTest {

    private static final String TARGET = "qto-app";
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration HANDLER_DURATION = Duration.ofMillis(300);
    private static final int QUERIES = 20;
    /**
     * Not a divisor of a second, so the queries are published at every fraction of the second.
     */
    private static final long PAUSE_MILLIS = 137;

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    @Configuration
    static class SlowQueryHandlers {
        @Bean
        BrokerConfig brokerConfig() {
            return new BrokerConfig(false, true, true, REPLY_TIMEOUT);
        }

        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .serveQuery("scenario.query", query -> Mono.delay(HANDLER_DURATION).thenReturn("ok"),
                            String.class);
        }
    }

    @Test
    void aQueryAnsweredWithinItsReplyTimeoutIsNotDiscarded() throws Exception {
        try (AnnotationConfigApplicationContext context = RabbitScenarioSupport.startWithHandlers(RABBIT, "qto",
                false, true, Map.of("app.async.app.direct.discardTimeoutQueries", "true"),
                SlowQueryHandlers.class, SendDirect.class, ListenQueries.class)) {
            RabbitScenarioSupport.awaitConsumers(RABBIT, TARGET + ".query");
            DirectAsyncGateway gateway = context.getBean(DirectAsyncGateway.class);

            int answered = 0;
            for (int i = 0; i < QUERIES; i++) {
                String reply = gateway.requestReply(new AsyncQuery<>("scenario.query", "q" + i), TARGET, String.class)
                        .onErrorResume(error -> Mono.empty())
                        .block(Duration.ofSeconds(5));
                if ("ok".equals(reply)) {
                    answered++;
                }
                Thread.sleep(PAUSE_MILLIS);
            }

            System.out.printf("[REAL BROKER] queries of %d ms with a reply timeout of %d ms: answered=%d/%d%n",
                    HANDLER_DURATION.toMillis(), REPLY_TIMEOUT.toMillis(), answered, QUERIES);
            assertThat(answered).as("queries answered within their reply timeout").isEqualTo(QUERIES);
        }
    }
}
