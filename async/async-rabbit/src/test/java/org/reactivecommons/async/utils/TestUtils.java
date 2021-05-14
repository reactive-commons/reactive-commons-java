package org.reactivecommons.async.utils;

import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.ConsumeOptions;
import reactor.rabbitmq.Receiver;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static reactor.core.publisher.Flux.defer;

public class TestUtils {

    private TestUtils() {
    }

    public static void instructSafeReceiverMock(final Receiver receiver, final Flux<AcknowledgableDelivery> source) {
        final AtomicBoolean flag = new AtomicBoolean(true);
        when(receiver.consumeManualAck(Mockito.anyString(), any(ConsumeOptions.class))).thenAnswer(invocation -> defer(() -> {
            if (flag.getAndSet(false)) {
                return source;
            } else {
                return Flux.never();
            }
        }));
    }

}
