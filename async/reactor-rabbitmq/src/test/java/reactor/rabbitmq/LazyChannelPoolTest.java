package reactor.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.RecoverableChannel;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.io.IOException;
import java.time.Duration;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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

    @Test
    void closedPooledChannelIsNotHandedOutAgain() {
        lazyChannelPool = new LazyChannelPool(Mono.just(connection),
                new ChannelPoolOptions().maxCacheSize(2).subscriptionScheduler(Schedulers.immediate()));
        Channel first = lazyChannelPool.getChannelMono().block();
        assertThat(first).isSameAs(channel1);
        lazyChannelPool.getChannelCloseHandler().accept(SignalType.ON_COMPLETE, first); // back to the pool

        // closed by a channel-level error while idle: it will never be usable again
        when(channel1.isOpen()).thenReturn(false);
        when(channel1.getCloseReason()).thenReturn(new ShutdownSignalException(false, false, null, channel1));

        assertThat(lazyChannelPool.getChannelMono().block()).isSameAs(channel2);
    }

    @Test
    void pooledChannelClosedByAConnectionFailureIsKeptBecauseItWillBeRecovered() throws IOException {
        RecoverableChannel recoverable = mock(RecoverableChannel.class);
        when(recoverable.isOpen()).thenReturn(true);
        when(recoverable.getConnection()).thenReturn(connection);
        when(connection.createChannel()).thenReturn(recoverable, channel2);
        lazyChannelPool = new LazyChannelPool(Mono.just(connection),
                new ChannelPoolOptions().maxCacheSize(2).subscriptionScheduler(Schedulers.immediate()));
        Channel first = lazyChannelPool.getChannelMono().block();
        lazyChannelPool.getChannelCloseHandler().accept(SignalType.ON_COMPLETE, first);

        when(recoverable.isOpen()).thenReturn(false);
        when(recoverable.getCloseReason()).thenReturn(new ShutdownSignalException(true, false, null, connection));

        assertThat(lazyChannelPool.getChannelMono().block()).isSameAs(recoverable);
    }

    @Test
    void closedRecoverableChannelIsAbortedSoTheAutomaticRecoveryForgetsIt() throws Exception {
        lazyChannelPool = new LazyChannelPool(Mono.just(connection), new ChannelPoolOptions().maxCacheSize(2));
        RecoverableChannel recoverable = mock(RecoverableChannel.class);
        when(recoverable.isOpen()).thenReturn(false);

        lazyChannelPool.getChannelCloseHandler().accept(SignalType.CANCEL, recoverable);
        lazyChannelPool.getChannelCloseHandler().accept(SignalType.ON_ERROR, channel1);

        verify(recoverable).abort();
        verify(channel1, never()).abort();
        verify(channel1).close(); // an open channel is still closed normally
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

    @Test
    void closingThePoolReleasesItsPrivateScheduler() throws Exception {
        lazyChannelPool = new LazyChannelPool(Mono.just(connection), new ChannelPoolOptions());
        assertThat(lazyChannelPool.getChannelMono().block(ofSeconds(5))).isSameAs(channel1);
        var scheduler = subscriptionSchedulerOf(lazyChannelPool);
        assertThat(scheduler.isDisposed()).as("private scheduler while the pool is in use").isFalse();

        lazyChannelPool.close();

        assertThat(scheduler.isDisposed()).as("private scheduler once the pool is closed").isTrue();
    }

    @Test
    void closingThePoolKeepsTheSchedulerItWasGiven() {
        var scheduler = Schedulers.newSingle("given-pool-scheduler");
        try {
            lazyChannelPool = new LazyChannelPool(Mono.just(connection),
                    new ChannelPoolOptions().subscriptionScheduler(scheduler));

            lazyChannelPool.close();

            assertThat(scheduler.isDisposed()).as("scheduler given to the pool").isFalse();
        } finally {
            scheduler.dispose();
        }
    }

    private static reactor.core.scheduler.Scheduler subscriptionSchedulerOf(LazyChannelPool pool) throws Exception {
        var field = LazyChannelPool.class.getDeclaredField("subscriptionScheduler");
        field.setAccessible(true);
        return (reactor.core.scheduler.Scheduler) field.get(pool);
    }
}
