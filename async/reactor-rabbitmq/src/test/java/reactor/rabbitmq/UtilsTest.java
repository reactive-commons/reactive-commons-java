package reactor.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.RecoverableChannel;
import com.rabbitmq.client.RecoverableConnection;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UtilsTest {

    @Test
    void cacheReturnsElementIndefinitely() {
        var cached = Utils.cache(Mono.just("value"));
        assertThat(cached.block(Duration.ofSeconds(1))).isEqualTo("value");
        assertThat(cached.block(Duration.ofSeconds(1))).isEqualTo("value");
    }

    @Test
    void cacheDoesNotCacheErrors() {
        var counter = new java.util.concurrent.atomic.AtomicInteger(0);
        var mono = Utils.cache(Mono.defer(() -> {
            if (counter.incrementAndGet() == 1) {
                return Mono.error(new RuntimeException("first call"));
            }
            return Mono.just("ok");
        }));

        assertThat(mono.onErrorResume(e -> Mono.empty()).block(Duration.ofSeconds(1))).isNull();
        assertThat(mono.block(Duration.ofSeconds(1))).isEqualTo("ok");
    }

    @Test
    void isRecoverableConnectionReturnsTrueForRecoverable() {
        var recoverable = mock(RecoverableConnection.class);
        assertThat(Utils.isRecoverable((Connection) recoverable)).isTrue();
    }

    @Test
    void isRecoverableConnectionReturnsFalseForNonRecoverable() {
        var connection = mock(Connection.class);
        assertThat(Utils.isRecoverable(connection)).isFalse();
    }

    @Test
    void isRecoverableChannelReturnsTrueForRecoverable() {
        var recoverable = mock(RecoverableChannel.class);
        assertThat(Utils.isRecoverable((Channel) recoverable)).isTrue();
    }

    @Test
    void isRecoverableChannelReturnsFalseForNonRecoverable() {
        var channel = mock(Channel.class);
        assertThat(Utils.isRecoverable(channel)).isFalse();
    }
}
