package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQSetupUtilsTest {

    @Test
    void connectionTimeoutAndRequestedHeartbeatAreAppliedToTheConnectionFactory() {
        RabbitProperties connection = new RabbitProperties();
        connection.setConnectionTimeout(Duration.ofSeconds(7));
        connection.setRequestedHeartbeat(Duration.ofSeconds(30));

        ConnectionFactory factory = connectionFactory(connection);

        assertThat(factory.getConnectionTimeout()).isEqualTo(7_000);
        assertThat(factory.getRequestedHeartbeat()).isEqualTo(30);
    }

    @Test
    void clientDefaultsAreKeptWhenTheyAreNotConfigured() {
        ConnectionFactory factory = connectionFactory(new RabbitProperties());

        assertThat(factory.getConnectionTimeout()).isEqualTo(ConnectionFactory.DEFAULT_CONNECTION_TIMEOUT);
        assertThat(factory.getRequestedHeartbeat()).isEqualTo(ConnectionFactory.DEFAULT_HEARTBEAT);
    }

    /**
     * Zero is passed as is to the client. Note that it only disables heartbeats if the broker heartbeat is zero too:
     * when either side requests zero, the client negotiates the greater value (RabbitMQ heartbeats guide).
     */
    @Test
    void zeroHeartbeatIsPassedToTheClient() {
        RabbitProperties connection = new RabbitProperties();
        connection.setRequestedHeartbeat(Duration.ZERO);

        assertThat(connectionFactory(connection).getRequestedHeartbeat()).isZero();
    }

    private static ConnectionFactory connectionFactory(RabbitProperties connection) {
        AsyncProps props = new AsyncProps();
        props.setAppName("test-app");
        props.setConnectionProperties(connection);
        return RabbitMQSetupUtils.connectionFactoryProvider(props, (cf, p) -> cf).getConnectionFactory();
    }
}
