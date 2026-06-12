package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GenericMessageListenerRecoveryTest {

    @Mock
    private Receiver receiver;

    @Mock
    private TopologyCreator topologyCreator;

    @Mock
    private DiscardNotifier discardNotifier;

    @Mock
    private CustomReporter errorReporter;

    private final AtomicInteger activeConsumers = new AtomicInteger(0);
    private final AtomicInteger totalSubscriptions = new AtomicInteger(0);

    private StubGenericMessageListener messageListener;

    @BeforeEach
    void init() {
        ReactiveMessageListener reactiveMessageListener = new ReactiveMessageListener(receiver, topologyCreator);
        messageListener = new StubGenericMessageListener(
                "test-queue", reactiveMessageListener, false, false, 10,
                discardNotifier, "command", errorReporter
        );

        // A never-ending flux that tracks how many consumers are currently subscribed.
        Flux<AcknowledgableDelivery> trackingFlux = Flux.<AcknowledgableDelivery>never()
                .doOnSubscribe(s -> {
                    activeConsumers.incrementAndGet();
                    totalSubscriptions.incrementAndGet();
                })
                .doOnCancel(activeConsumers::decrementAndGet);

        when(receiver.consumeManualAck(anyString(), any(ConsumeOptions.class)))
                .thenReturn(trackingFlux);
    }

    @Test
    void shouldNotDuplicateConsumersWhenQueueIsRecovered() throws Exception {
        messageListener.startListener();

        // Only one consumer should be active after starting.
        assertThat(activeConsumers.get()).isEqualTo(1);
        assertThat(totalSubscriptions.get()).isEqualTo(1);

        ShutdownListener shutdownListener = captureShutdownListener();

        // Simulate the queue going down and being recreated several times.
        for (int cycle = 1; cycle <= 5; cycle++) {
            shutdownListener.shutdownCompleted(mockShutdownCause());

            // After each recovery there must remain exactly one active consumer,
            // the old subscription must be disposed before the new one takes over.
            assertThat(activeConsumers.get())
                    .as("active consumers after recovery cycle %d", cycle)
                    .isEqualTo(1);
            assertThat(totalSubscriptions.get())
                    .as("a new subscription should be created on each recovery cycle")
                    .isEqualTo(cycle + 1);
        }
    }

    private ShutdownListener captureShutdownListener() throws Exception {
        ArgumentCaptor<ConsumeOptions> optionsCaptor = ArgumentCaptor.forClass(ConsumeOptions.class);
        verify(receiver).consumeManualAck(anyString(), optionsCaptor.capture());

        Channel channel = mockClosedChannelWithOpenConnection();
        extractChannelCallback(optionsCaptor.getValue()).accept(channel);

        ArgumentCaptor<ShutdownListener> listenerCaptor = ArgumentCaptor.forClass(ShutdownListener.class);
        verify(channel).addShutdownListener(listenerCaptor.capture());
        return listenerCaptor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Consumer<Channel> extractChannelCallback(ConsumeOptions options) throws Exception {
        Field field = ConsumeOptions.class.getDeclaredField("channelCallback");
        field.setAccessible(true);
        return (Consumer<Channel>) field.get(options);
    }

    private Channel mockClosedChannelWithOpenConnection() {
        Channel channel = Mockito.mock(Channel.class);
        Connection connection = Mockito.mock(Connection.class);
        lenient().when(channel.getConnection()).thenReturn(connection);
        lenient().when(channel.isOpen()).thenReturn(false);
        lenient().when(connection.isOpen()).thenReturn(true);
        return channel;
    }

    private ShutdownSignalException mockShutdownCause() {
        return Mockito.mock(ShutdownSignalException.class);
    }

    static class StubGenericMessageListener extends GenericMessageListener {

        public StubGenericMessageListener(String queueName, ReactiveMessageListener listener, boolean useDLQRetries,
                                          boolean createTopology, long maxRetries, DiscardNotifier discardNotifier,
                                          String objectType, CustomReporter errorReporter) {
            super(queueName, listener, useDLQRetries, createTopology, maxRetries, 200, discardNotifier,
                    objectType, errorReporter);
        }

        @Override
        public Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
            return message -> Mono.empty();
        }

        @Override
        protected String getExecutorPath(AcknowledgableDelivery msj) {
            return "test-path";
        }

        @Override
        protected Object parseMessageForReporter(Message msj) {
            return null;
        }

        @Override
        protected String getKind() {
            return "stub";
        }
    }
}
