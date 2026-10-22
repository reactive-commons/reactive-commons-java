package org.reactivecommons.async.rabbit.communications;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.java.Log;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;

import java.util.logging.Level;

@Log
public class UnroutableMessageNotifier {
    private static final String M_UNROUTABLE = "reactive_commons.publish.unroutable.messages";
    private static final String M_EMIT_FAILED = "reactive_commons.publish.unroutable.emit.failed";
    private static final String M_PROCESS_ERROR = "reactive_commons.publish.unroutable.process.error";

    private final Sinks.Many<OutboundMessageResult<OutboundMessage>> sink;
    private final UnroutableMetrics metrics;
    private volatile Disposable currentSubscription;

    public UnroutableMessageNotifier() {
        this(null, null);
    }

    /**
     * @param registry where the unroutable counters are registered, {@code null} for none
     * @param domain   tag of the counters; {@code null} when the notifier is shared by every domain
     */
    public UnroutableMessageNotifier(MeterRegistry registry, String domain) {
        this.sink = Sinks.many().multicast().onBackpressureBuffer();
        this.metrics = new UnroutableMetrics(registry, domain);
    }

    /**
     * Synchronized: the default notifier is shared by every domain, so the returns of several connections may arrive
     * at the same time, and a {@link Sinks.Many} rejects concurrent emissions ({@code FAIL_NON_SERIALIZED}) instead of
     * queueing them: only a full buffer may fail an emission.
     */
    public synchronized void notifyUnroutableMessage(OutboundMessageResult<OutboundMessage> message) {
        metrics.recordUnroutable();
        if (sink.tryEmitNext(message).isFailure()) {
            metrics.recordEmitFailure();
            log.warning("Failed to emit unroutable message: " + message);
        }
    }

    public void listenToUnroutableMessages(UnroutableMessageHandler handler) {
        if (currentSubscription != null && !currentSubscription.isDisposed()) {
            currentSubscription.dispose();
        }
        currentSubscription = sink.asFlux()
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(handler::processMessage)
                .onErrorContinue((throwable, o) -> {
                    metrics.recordProcessingError();
                    log.severe("Error processing unroutable message: " + throwable.getMessage());
                })
                .subscribe();
    }

    /**
     * Counters of the unroutable-message path: every mandatory message the broker returned, failures emitting it
     * to the notifications sink and errors of the processors subscribed to it.
     */
    private static final class UnroutableMetrics {
        private final Counter unroutable;
        private final Counter emitFailed;
        private final Counter processError;

        UnroutableMetrics(MeterRegistry registry, String domain) {
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

        private static Counter safeCounter(MeterRegistry registry, String name, Tags tags) {
            try {
                return Counter.builder(name).tags(tags).register(registry);
            } catch (Exception e) {
                // never let a mis-wired registry break the publishing path; metrics are best-effort
                log.log(Level.WARNING, e, () -> "Could not register " + name);
                return null;
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
