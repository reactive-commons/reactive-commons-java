package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RabbitMQSetupUtilsTest {

    /**
     * Opening a connection blocks (TCP, TLS and AMQP handshakes, up to the connection timeout): every attempt, the
     * retries included, runs on a thread meant for blocking calls, never on the non-blocking parallel threads the
     * retry backoff is timed on.
     */
    @Test
    void everyConnectionAttemptRunsOnAThreadMeantForBlockingCalls() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        List<String> attempts = new CopyOnWriteArrayList<>();
        when(factory.newConnection(anyString())).thenAnswer(invocation -> {
            attempts.add(Thread.currentThread().getName());
            if (attempts.size() <= 2) {
                throw new IOException("broker unreachable");
            }
            return connection;
        });
        RabbitMQSetupUtils.retainConnections(factory);
        try {
            assertThat(RabbitMQSetupUtils.listenerConnectionMono(() -> factory, "test-app")
                    .block(Duration.ofSeconds(10))).isSameAs(connection);
        } finally {
            RabbitMQSetupUtils.releaseConnections(factory);
        }

        assertThat(attempts).as("first attempt + 2 retries").hasSize(3)
                .allSatisfy(thread -> assertThat(thread).startsWith("boundedElastic"));
    }

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
    void heartbeatDefaultsToTenSecondsWhileTheClientConnectionTimeoutIsKept() {
        ConnectionFactory factory = connectionFactory(new RabbitProperties());

        assertThat(factory.getConnectionTimeout()).isEqualTo(ConnectionFactory.DEFAULT_CONNECTION_TIMEOUT);
        assertThat(factory.getRequestedHeartbeat()).isEqualTo(10);
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

    @Test
    void sslAlgorithmRestrictsTheEnabledProtocolsOfTheConnection() throws Exception {
        RabbitProperties connection = new RabbitProperties();
        connection.getSsl().setEnabled(true);
        connection.getSsl().setAlgorithm("TLSv1.2");

        SSLEngine engine = sslEngine(connectionFactory(connection));

        assertThat(engine.getEnabledProtocols()).containsExactly("TLSv1.2");
    }

    @Test
    void sslEnabledWithoutAnAlgorithmKeepsTheClientLibraryDefaultProtocols() throws Exception {
        RabbitProperties connection = new RabbitProperties();
        connection.getSsl().setEnabled(true);

        SSLEngine engine = sslEngine(connectionFactory(connection));

        assertThat(engine.getEnabledProtocols()).contains("TLSv1.2", "TLSv1.3");
    }

    private static ConnectionFactory connectionFactory(RabbitProperties connection) {
        AsyncProps props = new AsyncProps();
        props.setAppName("test-app");
        props.setConnectionProperties(connection);
        return RabbitMQSetupUtils.connectionFactoryProvider(props, (cf, p) -> cf).getConnectionFactory();
    }

    /**
     * The {@code SslContext} configured on the Netty connection factory is only reachable through the private
     * {@code sslContextFactory} field, there is no public getter for it.
     */
    @SuppressWarnings("unchecked")
    private static SSLEngine sslEngine(ConnectionFactory factory) throws Exception {
        ConnectionFactory.NettyConfiguration nettyConfiguration = factory.netty();
        Field field = ConnectionFactory.NettyConfiguration.class.getDeclaredField("sslContextFactory");
        field.setAccessible(true);
        Function<String, SslContext> sslContextFactory = (Function<String, SslContext>) field.get(nettyConfiguration);
        return sslContextFactory.apply("test-connection").newEngine(ByteBufAllocator.DEFAULT);
    }
}
