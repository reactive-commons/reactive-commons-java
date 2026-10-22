package org.reactivecommons.async.rabbit.communications;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class UnroutableMessageNotifierTest {

    @Spy
    private UnroutableMessageNotifier unroutableMessageNotifier;

    @Mock
    private Sinks.Many<OutboundMessageResult<OutboundMessage>> sink;

    @Mock
    private OutboundMessageResult<OutboundMessage> messageResult;

    @Mock
    private UnroutableMessageHandler handler;

    @Captor
    private ArgumentCaptor<OutboundMessageResult<OutboundMessage>> messageCaptor;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        // the registry is injected, then the sink is replaced by a mock
        unroutableMessageNotifier = new UnroutableMessageNotifier(registry, "domain");
        // Inject the sink mock using a spy to verify it
        try {
            java.lang.reflect.Field sinkField = UnroutableMessageNotifier.class.getDeclaredField("sink");
            sinkField.setAccessible(true);
            sinkField.set(unroutableMessageNotifier, sink);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldEmitUnroutableMessageSuccessfully() {
        when(sink.tryEmitNext(messageResult)).thenReturn(Sinks.EmitResult.OK);

        unroutableMessageNotifier.notifyUnroutableMessage(messageResult);

        verify(sink).tryEmitNext(messageResult);
        assertThat(counter("reactive_commons.publish.unroutable.messages")).isEqualTo(1.0);
        assertThat(counter("reactive_commons.publish.unroutable.emit.failed")).isZero();
    }

    @Test
    void shouldNotThrowWhenEmissionFails() {
        when(sink.tryEmitNext(messageResult)).thenReturn(Sinks.EmitResult.FAIL_NON_SERIALIZED);

        unroutableMessageNotifier.notifyUnroutableMessage(messageResult);

        verify(sink).tryEmitNext(messageResult);
        assertThat(counter("reactive_commons.publish.unroutable.messages")).isEqualTo(1.0);
        assertThat(counter("reactive_commons.publish.unroutable.emit.failed")).isEqualTo(1.0);
    }

    @Test
    void shouldSubscribeHandlerToFluxOfMessages() {
        final Flux<OutboundMessageResult<OutboundMessage>> messageResultFlux = Flux.just(messageResult);
        when(sink.asFlux()).thenReturn(messageResultFlux);
        when(handler.processMessage(any(OutboundMessageResult.class))).thenReturn(Mono.empty());

        unroutableMessageNotifier.listenToUnroutableMessages(handler);

        verify(handler, timeout(1000)).processMessage(messageCaptor.capture());
        assertThat(messageCaptor.getValue()).isEqualTo(messageResult);
    }

    @Test
    void shouldDisposePreviousSubscriptionWhenNewHandlerIsSubscribed() throws Exception {
        Disposable firstSubscription = mock(Disposable.class);
        when(firstSubscription.isDisposed()).thenReturn(false);

        Field field = UnroutableMessageNotifier.class.getDeclaredField("currentSubscription");
        field.setAccessible(true);
        field.set(unroutableMessageNotifier, firstSubscription);

        when(sink.asFlux()).thenReturn(Flux.never());

        unroutableMessageNotifier.listenToUnroutableMessages(handler);

        verify(firstSubscription).dispose();
    }

    @Test
    void shouldContinueProcessingWhenHandlerFails() {
        OutboundMessageResult<OutboundMessage> messageResult2 = mock(OutboundMessageResult.class);
        when(sink.asFlux()).thenReturn(Flux.just(messageResult, messageResult2));
        when(handler.processMessage(messageResult)).thenReturn(Mono.error(new RuntimeException("Processing Error")));
        when(handler.processMessage(messageResult2)).thenReturn(Mono.empty());

        unroutableMessageNotifier.listenToUnroutableMessages(handler);

        verify(handler, timeout(1000).times(1)).processMessage(messageResult);
        verify(handler, timeout(1000).times(1)).processMessage(messageResult2);
        assertThat(counter("reactive_commons.publish.unroutable.process.error")).isEqualTo(1.0);
    }

    private double counter(String name) {
        return registry.find(name).tag("domain", "domain").counter().count();
    }
}