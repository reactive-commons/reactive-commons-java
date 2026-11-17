package org.reactivecommons.async.kafka.listeners;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.EventExecutor;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.kafka.communications.PartitionFlowControl;
import org.reactivecommons.async.kafka.communications.ReactiveMessageListener;
import org.reactivecommons.async.kafka.communications.topology.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverPartition;
import reactor.kafka.receiver.ReceiverRecord;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Gives every notification to every instance of the application. Each instance reads every partition of the
 * notifications by itself, without a consumer group: none is created in the broker, and nothing is committed.
 * <p>
 * The partitions are read from their end when the listener starts, so an instance gets the notifications published
 * since then. The position of each partition is kept in memory instead: when the flow is subscribed again (after a
 * failure, or because the topics got more partitions) it goes on from there, and a partition added since the start is
 * read from its beginning, so no notification published meanwhile is skipped.
 */
@Slf4j
public class ApplicationNotificationsListener extends GenericMessageListener {

    /**
     * How often the partitions of the topics are checked, to read the ones added.
     */
    static final Duration PARTITIONS_CHECK_INTERVAL = Duration.ofSeconds(30);

    private final MessageConverter messageConverter;
    private final HandlerResolver resolver;
    private final ReactiveMessageListener receiver;
    private final List<String> notificationNames;
    private final Map<TopicPartition, Long> positions = new ConcurrentHashMap<>();

    public ApplicationNotificationsListener(ListenerContext<ReactiveMessageListener> context) {
        this(context,  null);
    }

    /**
     * @param schedulers the threads shared with the other listeners of the domain, or {@code null} for threads of its
     *                   own
     */
    public ApplicationNotificationsListener(ListenerContext<ReactiveMessageListener> context,
                                            KafkaListenerSchedulers schedulers) {
        super(context, "event", null, context.resolver().getNotificationNames(), schedulers);
        this.resolver = context.resolver();
        this.messageConverter = context.converter();
        this.receiver = context.listener();
        this.notificationNames = context.resolver().getNotificationNames();
    }

    /**
     * Reads every partition the topics have now, and completes when they get more, so the flow is subscribed again
     * with all of them.
     */
    @Override
    protected Flux<ReceiverRecord<String, byte[]>> receive(TopologyCreator creator, PartitionFlowControl flowControl) {
        return creator.partitionsOf(notificationNames)
                .flatMapMany(partitions -> receiver.listenAssigned(partitions, this::position, flowControl)
                        .takeUntilOther(partitionsChanged(creator, partitions)));
    }

    /**
     * Positions the partitions just assigned, on the thread of the consumer.
     */
    private void position(Collection<ReceiverPartition> assigned) {
        // Nothing read yet: the listener starts now
        boolean starting = positions.isEmpty();
        for (ReceiverPartition partition : assigned) {
            Long position = positions.get(partition.topicPartition());
            if (position != null) {
                partition.seek(position);
            } else if (starting) {
                partition.seekToEnd();
            } else {
                // Added since the listener started: everything in it was published since then
                partition.seekToBeginning();
            }
            // Resolved now, not on the first fetch: the notifications published from now on are read
            positions.put(partition.topicPartition(), partition.position());
        }
    }

    private Mono<List<TopicPartition>> partitionsChanged(TopologyCreator creator, List<TopicPartition> assigned) {
        Set<TopicPartition> current = Set.copyOf(assigned);
        return Flux.interval(PARTITIONS_CHECK_INTERVAL, handlerScheduler())
                .concatMap(tick -> creator.partitionsOf(notificationNames)
                        .onErrorResume(err -> {
                            log.debug("Unable to check the partitions of the notifications {}: {}",
                                    notificationNames, err.toString());
                            return Mono.empty();
                        }))
                .filter(partitions -> !current.equals(Set.copyOf(partitions)))
                .next()
                .doOnNext(partitions -> log.info("The notifications {} have now the partitions {}, reading all of "
                        + "them", notificationNames, partitions));
    }

    /**
     * Nothing is committed without a consumer group: the next position of the partition is kept in memory.
     */
    @Override
    protected void acknowledge(ReceiverRecord<String, byte[]> msj) {
        positions.put(new TopicPartition(msj.topic(), msj.partition()), msj.offset() + 1);
    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredEventListener<Object, Object> handler = resolver.getNotificationListener(executorPath);

        final EventExecutor<Object> executor = EventExecutor.of(handler, messageConverter);

        return msj -> executor
                .execute(msj)
                .cast(Object.class);
    }

    protected String getExecutorPath(ReceiverRecord<String, byte[]> msj) {
        return msj.topic();
    }

    @Override
    protected Object parseMessageForReporter(Message msj) {
        return messageConverter.readDomainEventStructure(msj);
    }
}
