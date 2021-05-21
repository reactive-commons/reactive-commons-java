package org.reactivecommons.async.utils;

import reactor.core.publisher.Flux;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static reactor.core.publisher.Flux.defer;


public class TestUtils {

    private TestUtils() {
    }

    public static void instructSafeReceiverMock(final Receiver receiver, final Flux<AcknowledgableDelivery> source) {
        final AtomicReference<Flux<AcknowledgableDelivery>> sourceReference = new AtomicReference<>(source);

        when(receiver.consumeManualAck(anyString(), any(ConsumeOptions.class)))
                .thenAnswer(invocation -> defer(() -> sourceReference.getAndSet(Flux.never())));
    }

}
