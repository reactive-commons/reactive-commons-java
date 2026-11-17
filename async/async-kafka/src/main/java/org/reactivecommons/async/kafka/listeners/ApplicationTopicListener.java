package org.reactivecommons.async.kafka.listeners;

import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.kafka.KafkaMessage;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Listens to Kafka topics directly, so each handler receives the {@link Message} exactly as it travels in its topic.
 * The topics share one consumer, which gives each record to the handler of its topic.
 */
public class ApplicationTopicListener extends GenericMessageListener {

    private final Map<String, RegisteredQueueListener> registeredListeners;
    private final ReactiveMessageSender dlqSender;

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
     */
    @Override
    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        return Flux.fromIterable(registeredListeners.values())
                .concatMap(listener -> listener.topologyHandlerSetup().setup(creator))
                .then();
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
     * Publishes the record as it is (key, body and headers), plus the {@code dlqHeaders}, to the DLQ of its topic,
     * {@code <topic>.dlq}: a raw message has no structure the notifier could rely on. The topology setup registered
     * with the handler must create that topic: until it exists, the message is kept and sent again.
     */
    @Override
    protected Mono<Void> notifyDiscard(Message message, ReceiverRecord<String, byte[]> msj,
                                       Map<String, Object> dlqHeaders) {
        if (dlqSender == null) {
            return super.notifyDiscard(message, msj, dlqHeaders);
        }
        // Every header of the record, those without value included, plus the DLQ headers
        Map<String, Object> headers = new HashMap<>(message.getProperties().getHeaders());
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
