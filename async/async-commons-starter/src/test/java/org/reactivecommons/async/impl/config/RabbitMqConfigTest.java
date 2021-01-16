package org.reactivecommons.async.impl.config;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.impl.communications.Message;
import org.reactivecommons.async.impl.ext.CustomErrorReporter;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RabbitMqConfigTest {

    RabbitMqConfig config = new RabbitMqConfig(null);

    @Test
    public void retryInitialConnection() throws IOException, TimeoutException {
        final String connectionType = "sender";
        final String appName = "appName";
        final String connectionName = "appName sender";

        final AtomicInteger count = new AtomicInteger();
        final Connection connection = mock(Connection.class);
        ConnectionFactory factory = mock(ConnectionFactory.class);
        when(factory.newConnection(connectionName)).thenAnswer(invocation -> {
            if(count.incrementAndGet() == 10){
                return connection;
            }
            throw new RuntimeException();
        });
        StepVerifier.withVirtualTime(() -> config.createConnectionMono(factory, appName, connectionType))
            .thenAwait(Duration.ofMinutes(2))
            .expectNext(connection).verifyComplete();
    }

    @Test
    public void shouldCreateDefaultErrorReporter() {
        final CustomErrorReporter errorReporter = config.reactiveCommonsCustomErrorReporter();
        assertThat(errorReporter.reportError(mock(Throwable.class), mock(Message.class), mock(Command.class), true)).isNotNull();
        assertThat(errorReporter.reportError(mock(Throwable.class), mock(Message.class), mock(DomainEvent.class), true)).isNotNull();
        assertThat(errorReporter.reportError(mock(Throwable.class), mock(Message.class), mock(AsyncQuery.class), true)).isNotNull();
    }
}