package reactor.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.io.IOException;
import java.time.Duration;

import static java.time.Duration.ofSeconds;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LazyChannelPoolTest {

    LazyChannelPool lazyChannelPool;
    Connection connection;
    Channel channel1, channel2, channel3;

    @BeforeEach
    void setUp() throws IOException {
        connection = mock(Connection.class);
        when(connection.isOpen()).thenReturn(true);
        channel1 = channel(1);
        channel2 = channel(2);
        channel3 = channel(3);
        when(connection.createChannel()).thenReturn(channel1, channel2, channel3);
    }

    @Test
    void testChannelPoolLazyInitialization() throws Exception {
        var options = new ChannelPoolOptions()
                .maxCacheSize(2)
                .subscriptionScheduler(VirtualTimeScheduler.create());
        lazyChannelPool = new LazyChannelPool(Mono.just(connection), options);

        StepVerifier.withVirtualTime(() ->
                        Mono.when(
                                useChannelFromStartUntil(ofSeconds(1)),
                                useChannelBetween(ofSeconds(2), ofSeconds(3))
                        ))
                .expectSubscription()
                .thenAwait(ofSeconds(3))
                .verifyComplete();

        // channel1 used twice (created at 0, pooled at 1, reused at 2)
        verifyBasicPublish(channel1, 2);
        verifyBasicPublishNever(channel2);
        verify(channel1, never()).close();

        lazyChannelPool.close();

        verify(channel1).close();
        verify(channel2, never()).close();
        verify(channel1).clearConfirmListeners();
        verify(channel2, never()).clearConfirmListeners();
    }

    @Test
    void testChannelPoolExceedsMaxPoolSize() throws Exception {
        var options = new ChannelPoolOptions()
                .maxCacheSize(2)
                .subscriptionScheduler(VirtualTimeScheduler.create());
        lazyChannelPool = new LazyChannelPool(Mono.just(connection), options);

        StepVerifier.withVirtualTime(() ->
                        Mono.when(
                                useChannelBetween(ofSeconds(1), ofSeconds(4)),
                                useChannelBetween(ofSeconds(2), ofSeconds(5)),
                                useChannelBetween(ofSeconds(3), ofSeconds(6))
                        ))
                .expectSubscription()
                .thenAwait(ofSeconds(6))
                .verifyComplete();

        verifyBasicPublishOnce(channel1);
        verifyBasicPublishOnce(channel2);
        verifyBasicPublishOnce(channel3);
        verify(channel1, never()).close();
        verify(channel2, never()).close();
        verify(channel3).close(); // exceeded pool capacity

        lazyChannelPool.close();

        verify(channel1).close();
        verify(channel2).close();
    }

    @Test
    void testChannelPool() throws Exception {
        var options = new ChannelPoolOptions()
                .maxCacheSize(1)
                .subscriptionScheduler(VirtualTimeScheduler.create());
        lazyChannelPool = new LazyChannelPool(Mono.just(connection), options);

        StepVerifier.withVirtualTime(() ->
                        Mono.when(
                                useChannelFromStartUntil(ofSeconds(3)),
                                useChannelBetween(ofSeconds(1), ofSeconds(2)),
                                useChannelBetween(ofSeconds(4), ofSeconds(5))
                        ))
                .expectSubscription()
                .thenAwait(ofSeconds(5))
                .verifyComplete();

        verifyBasicPublishOnce(channel1);
        verifyBasicPublish(channel2, 2);
        verify(channel1).close(); // exceeded pool at second 3
        verify(channel2, never()).close();
        verify(channel1, never()).clearConfirmListeners();
        verify(channel2).clearConfirmListeners();

        lazyChannelPool.close();
        verify(channel2).close();
    }

    private Mono<Void> useChannelFromStartUntil(Duration until) {
        return useChannelBetween(Duration.ZERO, until);
    }

    private Mono<Void> useChannelBetween(Duration from, Duration to) {
        return Mono.delay(from)
                .then(lazyChannelPool.getChannelMono())
                .flatMap(channel ->
                        Mono.just(1)
                                .doOnNext(i -> {
                                    try {
                                        channel.basicPublish("", "", null, "".getBytes());
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                                .delayElement(to.minus(from))
                                .doFinally(signalType ->
                                        lazyChannelPool.getChannelCloseHandler().accept(signalType, channel)
                                )
                )
                .then();
    }

    private void verifyBasicPublishNever(Channel channel) throws Exception {
        verifyBasicPublish(channel, 0);
    }

    private void verifyBasicPublishOnce(Channel channel) throws Exception {
        verifyBasicPublish(channel, 1);
    }

    private void verifyBasicPublish(Channel channel, int times) throws Exception {
        verify(channel, times(times)).basicPublish(anyString(), anyString(),
                nullable(AMQP.BasicProperties.class), any(byte[].class));
    }

    private Channel channel(int channelNumber) {
        Channel channel = mock(Channel.class);
        when(channel.getChannelNumber()).thenReturn(channelNumber);
        when(channel.isOpen()).thenReturn(true);
        when(channel.getConnection()).thenReturn(connection);
        return channel;
    }
}
