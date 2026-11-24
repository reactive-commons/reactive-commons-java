package org.reactivecommons.async.kafka.listeners;

import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Listens to Kafka topics directly, so each handler receives the {@link Message} exactly as it travels in its topic.
 * The topics share one consumer, which gives each record to the handler of its topic.
 */
@Slf4j
public class ApplicationTopicListener extends GenericMessageListener {

    private final Map<String, RegisteredQueueListener> registeredListeners;
    private final ReactiveMessageSender dlqSender;
    /**
     * The topologies that failed and are being set up again in the background, and those background setups.
     */
    private final Set<String> topologiesBeingRetried = ConcurrentHashMap.newKeySet();
    private final Disposable.Composite topologyRetries = Disposables.composite();

    public ApplicationTopicListener(ListenerContext<ReactiveMessageListener> context,
                                    RegisteredQueueListener registeredListener, String groupId) {
        this(context, registeredListener, groupId, null);
    }

    /**
     * @param dlqSender sends a message that exhausted its retries, as it is, to the DLQ of its topic; when
     *                  {@code null}, the {@link org.reactivecommons.async.commons.DiscardNotifier} of the context
     *                  publishes it
     */
    public ApplicationTopicListener(ListenerContext<ReactiveMessageListener> context,
                                    RegisteredQueueListener registeredListener, String groupId,
                                    ReactiveMessageSender dlqSender) {
        this(context, List.of(registeredListener), groupId, dlqSender, null);
    }

    /**
     * Listens to the topics of every registered listener with one consumer.
     *
     * @param dlqSender  sends a message that exhausted its retries, as it is, to the DLQ of its topic; when
     *                   {@code null}, the {@link org.reactivecommons.async.commons.DiscardNotifier} of the context
     *                   publishes it
     * @param schedulers the threads shared with the other listeners of the domain, or {@code null} for threads of its
     *                   own
     */
    public ApplicationTopicListener(ListenerContext<ReactiveMessageListener> context,
                                    Collection<RegisteredQueueListener> registeredListeners, String groupId,
                                    ReactiveMessageSender dlqSender, KafkaListenerSchedulers schedulers) {
        this(context, byTopic(registeredListeners), groupId, dlqSender, schedulers);
    }

    private ApplicationTopicListener(ListenerContext<ReactiveMessageListener> context,
                                     Map<String, RegisteredQueueListener> registeredListeners, String groupId,
                                     ReactiveMessageSender dlqSender, KafkaListenerSchedulers schedulers) {
        super(context, "topic", groupId, List.copyOf(registeredListeners.keySet()), schedulers);
        this.registeredListeners = registeredListeners;
        this.dlqSender = dlqSender;
    }

    private static Map<String, RegisteredQueueListener> byTopic(Collection<RegisteredQueueListener> listeners) {
        Map<String, RegisteredQueueListener> byTopic = new LinkedHashMap<>();
        listeners.forEach(listener -> byTopic.put(listener.queueName(), listener));
        return byTopic;
    }

    /**
     * The topology setup registered with each handler owns the whole topology of its topic, the DLQ included, as with
     * a raw queue of RabbitMQ.
     * <p>
     * The setup of each topic runs on its own: one that fails is logged and set up again in the background, with the
     * same waits as a restart of the listener, until it succeeds or the listener stops, and the topics are consumed
     * meanwhile, so a topic whose topology cannot be set up does not keep the others of the domain from being
     * consumed. A topic being set up again in the background is skipped.
     */
    @Override
    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        return Flux.fromIterable(registeredListeners.values())
                .filter(listener -> !topologiesBeingRetried.contains(listener.queueName()))
                .concatMap(listener -> setUpTopology(listener, creator)
                        .onErrorResume(err -> {
                            retryTopologyInBackground(listener, creator, err);
                            return Mono.empty();
                        }))
                .then();
    }

    private static Mono<Void> setUpTopology(RegisteredQueueListener listener, TopologyCreator creator) {
        return Mono.defer(() -> listener.topologyHandlerSetup().setup(creator));
    }

    private void retryTopologyInBackground(RegisteredQueueListener listener, TopologyCreator creator,
                                           Throwable failure) {
        String topic = listener.queueName();
        log.error("Unable to set up the topology of topic {}, consuming the other topics and setting it up again in "
                + "the background: {}: {}", topic, failure.getClass().getName(), sanitize(failure.getMessage()));
        if (!topologiesBeingRetried.add(topic)) {
            return;
        }
        AtomicBoolean firstAttempt = new AtomicBoolean(true);
        Disposable.Swap retry = Disposables.swap();
        // Added before subscribing: once the listener stops, the composite disposes what is added to it
        topologyRetries.add(retry);
        retry.update(Mono.defer(() -> firstAttempt.getAndSet(false)
                        // The attempt that already failed starts the waits, from the first one
                        ? Mono.<Void>error(failure)
                        : setUpTopology(listener, creator))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, RESTART_MIN_BACKOFF)
                        .maxBackoff(RESTART_MAX_BACKOFF)
                        .jitter(RESTART_JITTER)
                        .scheduler(handlerScheduler())
                        .doBeforeRetry(signal -> logTopologyRetry(topic, signal)))
                .doOnSuccess(done -> log.info("Topology of topic {} set up", topic))
                .doFinally(signal -> {
                    topologiesBeingRetried.remove(topic);
                    topologyRetries.remove(retry);
                })
                // Completes empty: only the error, once the listener stops, is left to handle
                .subscribe(null, err -> log.debug("Stopped setting up the topology of topic {} again: {}", topic,
                        sanitize(err.toString()))));
    }

    /**
     * The first failure, which started the setups in the background, is already logged.
     */
    private static void logTopologyRetry(String topic, Retry.RetrySignal signal) {
        if (signal.totalRetries() > 0) {
            log.debug("Unable to set up the topology of topic {} again (failure {} in a row): {}", topic,
                    signal.totalRetries() + 1, sanitize(signal.failure().toString()));
        }
    }

    /**
     * The message of an error may carry text from the broker: a line break in it would let it forge lines of the log.
     */
    private static String sanitize(String value) {
        return value == null ? null : value.replace('\r', '_').replace('\n', '_');
    }

    /**
     * Also stops setting up again, in the background, the topologies that failed.
     */
    @Override
    public void stop() {
        topologyRetries.dispose();
        super.stop();
    }

    /**
     * A raw topic may be a compacted one: its handler receives the records without value (tombstones), with a
     * {@code null} body, so it can delete what it keeps for their key.
     */
    @Override
    protected boolean skipsTombstones() {
        return false;
    }

    /**
     * Publishes the record to the DLQ of its topic, {@code <topic>.dlq}, with its key, its body and the bytes of each
     * of its headers as they are (a header without value stays without value; of a key repeated in the record, only
     * its last value is kept), plus the {@code dlqHeaders}, which replace a header of the record with the same key: a
     * raw message has no structure the notifier could rely on. The topology setup registered with the handler must
     * create that topic: until it exists, the message is kept and sent again.
     */
    @Override
    protected Mono<Void> notifyDiscard(Message message, ReceiverRecord<String, byte[]> msj,
                                       Map<String, Object> dlqHeaders) {
        if (dlqSender == null) {
            return super.notifyDiscard(message, msj, dlqHeaders);
        }
        Map<String, Object> headers = new HashMap<>();
        // The bytes of the record, not the headers of the message, which are read as text: a binary value (e.g. the
        // id of a schema) would be corrupted. Of a repeated key the last value wins, as when the message is read
        msj.headers().forEach(header -> headers.put(header.key(), header.value()));
        headers.putAll(dlqHeaders);
        KafkaMessage.KafkaMessageProperties properties = new KafkaMessage.KafkaMessageProperties();
        properties.setTopic(msj.topic() + TopologyCreator.DLQ_SUFFIX);
        properties.setKey(msj.key());
        properties.setHeaders(headers);
        properties.setContentLength(message.getProperties().getContentLength());
        return dlqSender.send(new KafkaMessage(msj.value(), properties, null));
    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        RegisteredQueueListener registeredListener = registeredListeners.get(executorPath);
        if (registeredListener == null) {
            throw new IllegalStateException("No handler is registered for topic " + executorPath);
        }
        return message -> registeredListener.handler().handle(message).cast(Object.class);
    }

    @Override
    protected String getExecutorPath(ReceiverRecord<String, byte[]> msj) {
        return msj.topic();
    }

    @Override
    protected Object parseMessageForReporter(Message message) {
        return message;
    }
}
