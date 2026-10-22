package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.java.Log;
import org.reactivecommons.async.rabbit.listeners.SubscriberStatus;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Polls the broker for the depth (ready messages) of the queues this application consumes and exports it as
 * {@code reactive_commons.consumer.queue.depth}, tagged with the domain and the queue.
 *
 * <p>RabbitMQ has no client-side queue length: the depth is read with a passive queue declare
 * ({@link Channel#queueDeclarePassive}), one RPC per queue every {@link #DEFAULT_POLL_INTERVAL}. The count is the
 * messages <em>ready</em> in the queue: deliveries already handed to a consumer but not acknowledged (e.g. while its
 * handler is busy) are not included, so this gauge complements the handler wait timer and the scheduler gauges, it
 * does not replace them.
 *
 * <p>The poller reuses the cached listener connection (it never opens a new one) and a channel of it, created
 * lazily. While the connection or the channel is unavailable the gauges read {@code NaN} and a single WARNING is
 * logged: a broker failure never affects consumption. Only this application's queues are polled; the queues that
 * stop being consumed are removed from the registry.
 */
@Log
public final class ConsumerQueueDepth implements AutoCloseable {

    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(10);
    private static final String METRIC = "reactive_commons.consumer.queue.depth";

    private final MeterRegistry registry;
    private final String domain;
    private final Supplier<Map<String, SubscriberStatus>> queuesSupplier;
    private final Map<String, QueueProbe> probes = new ConcurrentHashMap<>();
    private final AtomicBoolean warned = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Scheduler scheduler;
    private final Disposable subscription;

    private volatile Connection connection;
    private volatile Channel channel;

    public ConsumerQueueDepth(MeterRegistry registry, String domain, Mono<Connection> listenerConnection,
                              Supplier<Map<String, SubscriberStatus>> queuesSupplier) {
        this(registry, domain, listenerConnection, queuesSupplier, DEFAULT_POLL_INTERVAL);
    }

    ConsumerQueueDepth(MeterRegistry registry, String domain, Mono<Connection> listenerConnection,
                       Supplier<Map<String, SubscriberStatus>> queuesSupplier, Duration pollInterval) {
        this.registry = registry;
        this.domain = domain != null ? domain : "";
        this.queuesSupplier = queuesSupplier;
        // the connection is the cached one of the listeners: opened once and kept (and recovered) for the
        // lifetime of the connection factory, so waiting for its first value here never opens an extra connection
        listenerConnection.subscribe(established -> this.connection = established,
                error -> log.log(Level.FINE, error, () -> "Queue depth: no listener connection yet"));
        this.scheduler = Schedulers.newSingle("rc-queue-depth", true);
        this.subscription = Flux.interval(pollInterval, scheduler)
                .onBackpressureDrop() // a slow tick is skipped, never queued
                .subscribe(tick -> poll());
    }

    private void poll() {
        if (closed.get()) {
            return;
        }
        final Map<String, SubscriberStatus> queues = queuesSupplier.get();
        removeQueuesNoLongerConsumed(queues);
        if (queues.isEmpty()) {
            return;
        }
        final Connection current = connection;
        if (current == null || !current.isOpen()) {
            fail(new IllegalStateException("the listener connection is not available"));
            return;
        }
        Throwable firstError = null;
        try {
            Channel currentChannel = channel;
            if (currentChannel == null || !currentChannel.isOpen()) {
                currentChannel = current.createChannel();
                channel = currentChannel;
            }
            for (String queue : queues.keySet()) {
                final QueueProbe probe = probes.computeIfAbsent(queue, this::registerProbe);
                try {
                    probe.count().set((long) currentChannel.queueDeclarePassive(queue).getMessageCount());
                } catch (Exception e) {
                    probe.count().set(null); // unknown: the gauge reads NaN
                    if (firstError == null) {
                        firstError = e;
                    }
                }
            }
        } catch (Exception e) {
            firstError = e;
        }
        if (firstError != null) {
            // a failed passive declare closes its channel: get a new one on the next tick
            channel = null;
            fail(firstError);
        } else {
            warned.set(false);
        }
    }

    private QueueProbe registerProbe(String queue) {
        final AtomicReference<Long> count = new AtomicReference<>();
        Meter.Id id = null;
        try {
            id = Gauge.builder(METRIC, count, value -> {
                        final Long current = value.get();
                        return current == null ? Double.NaN : current.doubleValue();
                    })
                    .tags(Tags.of("domain", domain, "queue", queue))
                    .register(registry)
                    .getId();
        } catch (Exception e) {
            // never let a mis-wired registry break the polling; metrics are best-effort
            log.log(Level.WARNING, e, () -> "Could not register " + METRIC);
        }
        return new QueueProbe(count, id);
    }

    private void removeQueuesNoLongerConsumed(Map<String, SubscriberStatus> queues) {
        final Iterator<Map.Entry<String, QueueProbe>> iterator = probes.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, QueueProbe> entry = iterator.next();
            if (queues.containsKey(entry.getKey())) {
                continue;
            }
            iterator.remove();
            final Meter.Id id = entry.getValue().id();
            if (id != null) {
                try {
                    registry.remove(id);
                } catch (Exception e) {
                    log.log(Level.FINE, e, () -> "Could not remove the queue depth gauge of " + entry.getKey());
                }
            }
        }
    }

    private void fail(Throwable error) {
        if (warned.compareAndSet(false, true)) {
            log.log(Level.WARNING, error, () -> "Could not read the depth of the queues of domain " + domain
                    + ": the gauge will read NaN until the broker answers");
        } else {
            log.log(Level.FINE, error, () -> "Could not read the depth of the queues of domain " + domain);
        }
    }

    /**
     * Stops polling and closes the channel (never the connection: it is shared with the listeners), and removes
     * the gauges from the registry.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        subscription.dispose();
        scheduler.dispose();
        final Channel toClose = channel;
        channel = null;
        if (toClose != null) {
            try {
                toClose.close();
            } catch (Exception e) {
                log.log(Level.FINE, e, () -> "Could not close the queue depth channel");
            }
        }
        probes.values().forEach(probe -> {
            if (probe.id() != null) {
                try {
                    registry.remove(probe.id());
                } catch (Exception e) {
                    log.log(Level.FINE, e, () -> "Could not remove the queue depth gauge");
                }
            }
        });
        probes.clear();
    }

    private record QueueProbe(AtomicReference<Long> count, Meter.Id id) {
    }
}
