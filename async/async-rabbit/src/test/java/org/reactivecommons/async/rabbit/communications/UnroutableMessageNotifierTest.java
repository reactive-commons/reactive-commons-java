package org.reactivecommons.async.rabbit.communications;

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
    private Sinks.Many<OutboundMessageResult<MyOutboundMessage>> sink;

    @Mock
    private OutboundMessageResult<MyOutboundMessage> messageResult;

    @Mock
    private UnroutableMessageHandler handler;

    @Captor
    private ArgumentCaptor<OutboundMessageResult<MyOutboundMessage>> messageCaptor;

    @BeforeEach
    void setUp() {
        // Usar el constructor por defecto y espiar el sink interno
        unroutableMessageNotifier = new UnroutableMessageNotifier();
        // Inyectar el mock del sink usando un spy para poder verificarlo
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
    }

    @Test
    void shouldNotThrowWhenEmissionFails() {
        when(sink.tryEmitNext(messageResult)).thenReturn(Sinks.EmitResult.FAIL_NON_SERIALIZED);

        unroutableMessageNotifier.notifyUnroutableMessage(messageResult);

        verify(sink).tryEmitNext(messageResult);
    }

    @Test
    void shouldSubscribeHandlerToFluxOfMessages() {
        final Flux<OutboundMessageResult<MyOutboundMessage>> messageResultFlux = Flux.just(messageResult);
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
        OutboundMessageResult<MyOutboundMessage> messageResult2 = mock(OutboundMessageResult.class);
        when(sink.asFlux()).thenReturn(Flux.just(messageResult, messageResult2));
        when(handler.processMessage(messageResult)).thenReturn(Mono.error(new RuntimeException("Processing Error")));
        when(handler.processMessage(messageResult2)).thenReturn(Mono.empty());

        unroutableMessageNotifier.listenToUnroutableMessages(handler);

        verify(handler, timeout(1000).times(1)).processMessage(messageResult);
        verify(handler, timeout(1000).times(1)).processMessage(messageResult2);
    }
}