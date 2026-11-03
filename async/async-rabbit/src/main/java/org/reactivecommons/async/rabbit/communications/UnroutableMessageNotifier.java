package org.reactivecommons.async.rabbit.communications;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.rabbit.MeterUsage;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;


@Slf4j
public class UnroutableMessageNotifier {
    private static final String M_UNROUTABLE = "reactive_commons.publish.unroutable.messages";
    private static final String M_EMIT_FAILED = "reactive_commons.publish.unroutable.emit.failed";
    private static final String M_PROCESS_ERROR = "reactive_commons.publish.unroutable.process.error";

    private final Sinks.Many<OutboundMessageResult<OutboundMessage>> sink;
    private final UnroutableMetrics metrics;
    private final Object emitLock = new Object();
    private volatile Disposable currentSubscription;

    public UnroutableMessageNotifier() {
        this(null, null);
    }

    /**
     * @param registry where the unroutable counters are registered, {@code null} for none
     * @param domain   tag of the counters; {@code null} when the notifier is shared by every domain
     */
    public UnroutableMessageNotifier(MeterRegistry registry, String domain) {
        // no auto-cancel: a handler that starts listening after another one (whose subscription is disposed) would
        // otherwise find the sink terminated, and every unroutable message would fail to be emitted from then on
        this.sink = Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);
        this.metrics = new UnroutableMetrics(registry, domain);
    }

    /**
     * The emissions are serialized: the default notifier is shared by every domain, so the returns of several
     * connections may arrive at the same time, and a {@link Sinks.Many} rejects concurrent emissions
     * ({@code FAIL_NON_SERIALIZED}) instead of queueing them: only a full buffer may fail an emission. The lock is a
     * private one, not the notifier: a synchronized method would also lock a mock of it while Mockito waits for an
     * invocation of it ({@code verify(mock, timeout(...))}), blocking the invocation it waits for.
     */
    public void notifyUnroutableMessage(OutboundMessageResult<OutboundMessage> message) {
        metrics.recordUnroutable();
        final boolean emitted;
        synchronized (emitLock) {
            emitted = sink.tryEmitNext(message).isSuccess();
        }
        if (!emitted) {
            metrics.recordEmitFailure();
            log.warn("Failed to emit unroutable message: {}", message);
        }
    }

    /**
     * Makes the handler the one that processes the unroutable messages, instead of the previous one. It runs off the
     * thread that emits the returns, which is the I/O thread of the connection: a handler that blocks would otherwise
     * delay every publisher confirm and return of the connection. A handler that fails only fails its message.
     */
    public void listenToUnroutableMessages(UnroutableMessageHandler handler) {
        if (currentSubscription != null && !currentSubscription.isDisposed()) {
            currentSubscription.dispose();
        }
        currentSubscription = sink.asFlux()
                .publishOn(Schedulers.boundedElastic())
                .flatMap(message -> Mono.defer(() -> handler.processMessage(message))
                        .onErrorResume(throwable -> {
                            metrics.recordProcessingError();
                            log.error("Error processing unroutable message: {}", throwable.getMessage());
                            return Mono.empty();
                        }))
                .subscribe();
    }

    /**
     * Stops processing the unroutable messages and removes the counters of this notifier from the registry. Called by
     * Spring when the application context closes (inferred destroy method of the bean).
     */
    public void close() {
        final Disposable subscription = currentSubscription;
        if (subscription != null) {
            subscription.dispose();
        }
        metrics.close();
    }

    /**
     * Counters of the unroutable-message path: every mandatory message the broker returned, failures emitting it
     * to the notifications sink and errors of the processors subscribed to it.
     */
    private static final class UnroutableMetrics {
        private final MeterRegistry registry;
        private final Counter unroutable;
        private final Counter emitFailed;
        private final Counter processError;

        UnroutableMetrics(MeterRegistry registry, String domain) {
            this.registry = registry;
            if (registry == null) {
                this.unroutable = null;
                this.emitFailed = null;
                this.processError = null;
                return;
            }
            // always tagged: Prometheus requires every meter of a name to have the same tag keys
            final Tags tags = Tags.of("domain", domain != null ? domain : "");
            this.unroutable = safeCounter(registry, M_UNROUTABLE, tags);
            this.emitFailed = safeCounter(registry, M_EMIT_FAILED, tags);
            this.processError = safeCounter(registry, M_PROCESS_ERROR, tags);
        }

        private Counter safeCounter(MeterRegistry registry, String name, Tags tags) {
            try {
                return MeterUsage.register(registry, this, () -> Counter.builder(name).tags(tags).register(registry));
            } catch (Exception e) {
                // never let a mis-wired registry break the publishing path; metrics are best-effort
                log.warn("Could not register {}", name, e);
                return null;
            }
        }

        /**
         * Releases the counters of this notifier: removed from the registry unless another live notifier uses them
         * (see {@link MeterUsage}).
         */
        void close() {
            if (registry == null) {
                return;
            }
            try {
                MeterUsage.release(registry, this);
            } catch (Exception e) {
                log.warn("Could not remove the unroutable counters", e);
            }
        }

        void recordUnroutable() {
            if (unroutable != null) {
                unroutable.increment();
            }
        }

        void recordEmitFailure() {
            if (emitFailed != null) {
                emitFailed.increment();
            }
        }

        void recordProcessingError() {
            if (processError != null) {
                processError.increment();
            }
        }
    }
}
