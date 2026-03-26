package reactor.rabbitmq;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SendOptionsTest {

    @Test
    void defaultValues() {
        var opts = new SendOptions();
        assertThat(opts.getExceptionHandler()).isNotNull();
        assertThat(opts.getMaxInFlight()).isNull();
        assertThat(opts.isTrackReturned()).isFalse();
        assertThat(opts.getScheduler()).isEqualTo(Schedulers.immediate());
        assertThat(opts.getChannelMono()).isNull();
        assertThat(opts.getChannelCloseHandler()).isNull();
    }

    @Test
    void trackReturned() {
        var opts = new SendOptions().trackReturned(true);
        assertThat(opts.isTrackReturned()).isTrue();
    }

    @Test
    void maxInFlight() {
        var opts = new SendOptions().maxInFlight(256);
        assertThat(opts.getMaxInFlight()).isEqualTo(256);
    }

    @Test
    void maxInFlightWithScheduler() {
        var scheduler = Schedulers.boundedElastic();
        var opts = new SendOptions().maxInFlight(128, scheduler);
        assertThat(opts.getMaxInFlight()).isEqualTo(128);
        assertThat(opts.getScheduler()).isSameAs(scheduler);
    }

    @Test
    void channelMono() {
        Mono<Channel> mono = Mono.just(mock(Channel.class));
        var opts = new SendOptions().channelMono(mono);
        assertThat(opts.getChannelMono()).isSameAs(mono);
    }

    @Test
    void channelCloseHandler() {
        BiConsumer<SignalType, Channel> handler = (s, c) -> {};
        var opts = new SendOptions().channelCloseHandler(handler);
        assertThat(opts.getChannelCloseHandler()).isSameAs(handler);
    }

    @Test
    void channelPoolSetsMonoAndHandler() {
        var pool = mock(ChannelPool.class);
        Mono<Channel> mono = Mono.just(mock(Channel.class));
        BiConsumer<SignalType, Channel> handler = (s, c) -> {};
        doReturn(mono).when(pool).getChannelMono();
        doReturn(handler).when(pool).getChannelCloseHandler();

        var opts = new SendOptions().channelPool(pool);
        assertThat(opts.getChannelMono()).isSameAs(mono);
        assertThat(opts.getChannelCloseHandler()).isSameAs(handler);
    }
}
