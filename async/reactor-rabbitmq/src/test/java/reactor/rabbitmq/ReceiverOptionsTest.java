package reactor.rabbitmq;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReceiverOptionsTest {

    @Test
    void defaultValues() {
        var opts = new ReceiverOptions();
        assertThat(opts.getConnectionFactory()).isNotNull();
        assertThat(opts.getConnectionMono()).isNull();
        assertThat(opts.getConnectionSubscriptionScheduler()).isNull();
        assertThat(opts.getConnectionSupplier()).isNull();
        assertThat(opts.getConnectionMonoConfigurator()).isNotNull();
        assertThat(opts.getConnectionClosingTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void connectionFactory() {
        var factory = new ConnectionFactory();
        var opts = new ReceiverOptions().connectionFactory(factory);
        assertThat(opts.getConnectionFactory()).isSameAs(factory);
    }

    @Test
    void connectionMono() {
        Mono<Connection> mono = Mono.just(mock(Connection.class));
        var opts = new ReceiverOptions().connectionMono(mono);
        assertThat(opts.getConnectionMono()).isSameAs(mono);
    }

    @Test
    void connectionSubscriptionScheduler() {
        var scheduler = Schedulers.boundedElastic();
        var opts = new ReceiverOptions().connectionSubscriptionScheduler(scheduler);
        assertThat(opts.getConnectionSubscriptionScheduler()).isSameAs(scheduler);
    }

    @Test
    void connectionSupplier() throws Exception {
        var connection = mock(Connection.class);
        var opts = new ReceiverOptions().connectionSupplier(cf -> connection);
        assertThat(opts.getConnectionSupplier()).isNotNull();
        assertThat(opts.getConnectionSupplier().apply(null)).isSameAs(connection);
    }

    @Test
    void connectionSupplierWithExplicitFactory() throws Exception {
        var connection = mock(Connection.class);
        var factory = new ConnectionFactory();
        var opts = new ReceiverOptions().connectionSupplier(factory, cf -> connection);
        assertThat(opts.getConnectionSupplier().apply(null)).isSameAs(connection);
    }

    @Test
    void connectionMonoConfigurator() {
        UnaryOperator<Mono<? extends Connection>> configurator = Mono::cache;
        var opts = new ReceiverOptions().connectionMonoConfigurator(configurator);
        assertThat(opts.getConnectionMonoConfigurator()).isSameAs(configurator);
    }

    @Test
    void connectionMonoConfiguratorDefaultIsIdentity() {
        var opts = new ReceiverOptions();
        Mono<Connection> mono = Mono.just(mock(Connection.class));
        assertThat(opts.getConnectionMonoConfigurator().apply(mono)).isSameAs(mono);
    }

    @Test
    void connectionClosingTimeout() {
        var opts = new ReceiverOptions().connectionClosingTimeout(Duration.ofMillis(500));
        assertThat(opts.getConnectionClosingTimeout()).isEqualTo(Duration.ofMillis(500));
    }
}
