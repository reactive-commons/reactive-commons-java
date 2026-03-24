package reactor.rabbitmq;

import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChannelPoolFactoryTest {

    @Test
    void createChannelPoolWithDefaultOptions() {
        var connection = mock(Connection.class);
        var pool = ChannelPoolFactory.createChannelPool(Mono.just(connection));
        assertThat(pool).isInstanceOf(LazyChannelPool.class);
    }

    @Test
    void createChannelPoolWithCustomOptions() {
        var connection = mock(Connection.class);
        var options = new ChannelPoolOptions().maxCacheSize(10);
        var pool = ChannelPoolFactory.createChannelPool(Mono.just(connection), options);
        assertThat(pool).isInstanceOf(LazyChannelPool.class);
    }
}
