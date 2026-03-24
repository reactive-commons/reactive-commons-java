package reactor.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SenderOptionsTest {

    @Test
    void defaultValues() {
        var opts = new SenderOptions();
        assertThat(opts.getConnectionFactory()).isNotNull();
        assertThat(opts.getConnectionMono()).isNull();
        assertThat(opts.getChannelMono()).isNull();
        assertThat(opts.getChannelCloseHandler()).isNull();
        assertThat(opts.getResourceManagementScheduler()).isNull();
        assertThat(opts.getConnectionSubscriptionScheduler()).isNull();
        assertThat(opts.getResourceManagementChannelMono()).isNull();
        assertThat(opts.getConnectionSupplier()).isNull();
        assertThat(opts.getConnectionMonoConfigurator()).isNotNull();
        assertThat(opts.getConnectionClosingTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void connectionFactory() {
        var factory = new ConnectionFactory();
        var opts = new SenderOptions().connectionFactory(factory);
        assertThat(opts.getConnectionFactory()).isSameAs(factory);
    }

    @Test
    void connectionMono() {
        Mono<Connection> mono = Mono.just(mock(Connection.class));
        var opts = new SenderOptions().connectionMono(mono);
        assertThat(opts.getConnectionMono()).isSameAs(mono);
    }

    @Test
    void channelMono() {
        Mono<Channel> mono = Mono.just(mock(Channel.class));
        var opts = new SenderOptions().channelMono(mono);
        assertThat(opts.getChannelMono()).isSameAs(mono);
    }

    @Test
    void channelCloseHandler() {
        BiConsumer<SignalType, Channel> handler = (s, c) -> {};
        var opts = new SenderOptions().channelCloseHandler(handler);
        assertThat(opts.getChannelCloseHandler()).isSameAs(handler);
    }

    @Test
    void resourceManagementScheduler() {
        var scheduler = Schedulers.boundedElastic();
        var opts = new SenderOptions().resourceManagementScheduler(scheduler);
        assertThat(opts.getResourceManagementScheduler()).isSameAs(scheduler);
    }

    @Test
    void connectionSubscriptionScheduler() {
        var scheduler = Schedulers.boundedElastic();
        var opts = new SenderOptions().connectionSubscriptionScheduler(scheduler);
        assertThat(opts.getConnectionSubscriptionScheduler()).isSameAs(scheduler);
    }

    @Test
    void connectionSupplierWithFactory() throws Exception {
        var connection = mock(Connection.class);
        var opts = new SenderOptions().connectionSupplier(cf -> connection);
        assertThat(opts.getConnectionSupplier()).isNotNull();
        assertThat(opts.getConnectionSupplier().apply(null)).isSameAs(connection);
    }

    @Test
    void connectionSupplierWithExplicitFactory() throws Exception {
        var connection = mock(Connection.class);
        var factory = new ConnectionFactory();
        var opts = new SenderOptions().connectionSupplier(factory, cf -> connection);
        assertThat(opts.getConnectionSupplier().apply(null)).isSameAs(connection);
    }

    @Test
    void connectionMonoConfigurator() {
        UnaryOperator<Mono<? extends Connection>> configurator = Mono::cache;
        var opts = new SenderOptions().connectionMonoConfigurator(configurator);
        assertThat(opts.getConnectionMonoConfigurator()).isSameAs(configurator);
    }

    @Test
    void connectionMonoConfiguratorDefaultIsIdentity() {
        var opts = new SenderOptions();
        Mono<Connection> mono = Mono.just(mock(Connection.class));
        assertThat(opts.getConnectionMonoConfigurator().apply(mono)).isSameAs(mono);
    }

    @Test
    void resourceManagementChannelMono() {
        Mono<Channel> mono = Mono.just(mock(Channel.class));
        var opts = new SenderOptions().resourceManagementChannelMono(mono);
        assertThat(opts.getResourceManagementChannelMono()).isSameAs(mono);
    }

    @Test
    void connectionClosingTimeout() {
        var opts = new SenderOptions().connectionClosingTimeout(Duration.ofMillis(500));
        assertThat(opts.getConnectionClosingTimeout()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void channelPool() {
        var pool = mock(ChannelPool.class);
        Mono<Channel> mono = Mono.just(mock(Channel.class));
        BiConsumer<SignalType, Channel> handler = (s, c) -> {};
        doReturn(mono).when(pool).getChannelMono();
        doReturn(handler).when(pool).getChannelCloseHandler();

        var opts = new SenderOptions().channelPool(pool);
        assertThat(opts.getChannelMono()).isSameAs(mono);
        assertThat(opts.getChannelCloseHandler()).isSameAs(handler);
    }
}
