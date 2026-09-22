package org.reactivecommons.async.kafka.listeners;

import lombok.extern.java.Log;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueueListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;

import java.util.List;
import java.util.function.Function;

/**
 * Listens to a Kafka topic directly, so the handler receives the {@link Message} exactly as it travels in the topic.
 */
@Log
public class ApplicationTopicListener extends GenericMessageListener {

    private final RegisteredQueueListener registeredListener;
    private final String topic;

    public ApplicationTopicListener(ReactiveMessageListener listener,
                                    boolean withDLQRetry,
                                    boolean createTopology,
                                    long maxRetries,
                                    int retryDelay,
                                    RegisteredQueueListener registeredListener,
                                    DiscardNotifier discardNotifier,
                                    CustomReporter errorReporter,
                                    String groupId) {
        super(listener, withDLQRetry, createTopology, maxRetries, retryDelay, discardNotifier, "topic", errorReporter,
                groupId, List.of(registeredListener.queueName()));
        this.registeredListener = registeredListener;
        this.topic = registeredListener.queueName();
    }

    @Override
    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        return registeredListener.topologyHandlerSetup().setup(creator);
    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        return message -> registeredListener.handler().handle(message).cast(Object.class);
    }

    @Override
    protected String getExecutorPath(ReceiverRecord<String, byte[]> msj) {
        return topic;
    }

    @Override
    protected Object parseMessageForReporter(Message message) {
        return message;
    }
}
