package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.ext.DefaultCustomReporter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageNotifier;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.rabbit.discard.RabbitMQDiscardProviderImpl;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The discard provider shared by the domains, on a REAL RabbitMQ broker, when no sender of the default domain was
 * registered with it (e.g. a custom wiring): it opens a sender of its own, which must not outlive the broker providers
 * that publish their discards through it.
 * The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class DiscardProviderRabbitContainerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-alpine");

    private final RabbitJacksonMessageConverter converter = new RabbitJacksonMessageConverter(new JsonMapper());

    @Test
    void theSenderOpenedByTheDiscardProviderIsClosedWithTheLastProviderUsingIt() throws Exception {
        RabbitMQBrokerProviderFactory factory = new RabbitMQBrokerProviderFactory(new BrokerConfig(),
                new ReactiveReplyRouter(), converter, new SimpleMeterRegistry(), new DefaultCustomReporter(),
                RabbitMQDiscardProviderImpl::new, new UnroutableMessageNotifier(), (cf, props) -> cf);
        // the props of the default domain, whose broker provider is never created
        DiscardProvider discards = factory.getDiscardProvider(asyncProps("fb-default"));
        BrokerProvider<AsyncProps> first = factory.getProvider("first", asyncProps("fb-first"), discards);
        BrokerProvider<AsyncProps> second = factory.getProvider("second", asyncProps("fb-second"), discards);
        declareTheExchangeOfTheDiscardNotifications();

        // without a sender of the default domain, the shared notifier opens one of its own
        Mono.from(discards.get().tryNotifyDiscard(discardedEvent())).block(TIMEOUT);
        assertThat(awaitConnections("fb-default-", 1)).as("the sender of the discard provider").isTrue();

        first.close();
        Thread.sleep(1_000);
        assertThat(RabbitScenarioSupport.connectionsOf(RABBIT, "fb-default-")).as("still used by the second provider")
                .isEqualTo(1);
        second.close();

        boolean closed = awaitConnections("fb-default-", 0);
        System.out.println("[REAL BROKER] sender of the discard provider closed with its last provider: " + closed);
        assertThat(closed).as("not left open once no provider uses it").isTrue();
    }

    private static void declareTheExchangeOfTheDiscardNotifications() throws Exception {
        ConnectionFactory admin = new ConnectionFactory();
        admin.setHost(RABBIT.getHost());
        admin.setPort(RABBIT.getAmqpPort());
        admin.setUsername(RABBIT.getAdminUsername());
        admin.setPassword(RABBIT.getAdminPassword());
        try (Connection connection = admin.newConnection("fb-admin")) {
            connection.createChannel().exchangeDeclare("domainEvents", "topic", true);
        }
    }

    private static boolean awaitConnections(String connectionNamePrefix, int expected) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (RabbitScenarioSupport.connectionsOf(RABBIT, connectionNamePrefix) != expected) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(200);
        }
        return true;
    }

    private Message discardedEvent() {
        RabbitMessage.RabbitMessageProperties properties = new RabbitMessage.RabbitMessageProperties();
        properties.setContentType("application/json");
        return new RabbitMessage("{\"name\":\"failing.event\",\"eventId\":\"1\",\"data\":{}}"
                .getBytes(StandardCharsets.UTF_8), properties, "failing.event");
    }

    private static AsyncProps asyncProps(String appName) {
        RabbitProperties connection = new RabbitProperties();
        connection.setHost(RABBIT.getHost());
        connection.setPort(RABBIT.getAmqpPort());
        connection.setUsername(RABBIT.getAdminUsername());
        connection.setPassword(RABBIT.getAdminPassword());
        AsyncProps props = new AsyncProps();
        props.setAppName(appName);
        props.setConnectionProperties(connection);
        props.setBrokerConfigProps(new BrokerConfigProps(props));
        return props;
    }
}
