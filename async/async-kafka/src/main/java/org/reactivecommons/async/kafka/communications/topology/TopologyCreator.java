package org.reactivecommons.async.kafka.communications.topology;

import lombok.SneakyThrows;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.reactivecommons.async.kafka.communications.exceptions.TopicNotFoundException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Creates the topics of the listeners and checks that the topics sent to exist. Nothing is asked to the cluster when
 * it is built: the application starts even when the broker cannot be reached, and every check describes the topic it
 * needs, so the cluster is never listed (no permission to describe every topic is needed).
 */
public class TopologyCreator {
    public static final int TIMEOUT_MS = 60_000;
    public static final String DLQ_SUFFIX = ".dlq";
    /**
     * How long {@link #ensureTopic(String)} reports a topic found missing without asking the cluster again.
     */
    public static final Duration MISSING_TOPIC_TTL = Duration.ofSeconds(30);
    /**
     * How long a topic found (or created) is taken as existing without asking the cluster again: once it passes, a
     * topic deleted meanwhile is reported missing instead of being sent to until the producer times out.
     */
    public static final Duration EXISTING_TOPIC_TTL = Duration.ofMinutes(5);
    /**
     * How long closing waits for the requests still pending (e.g. a topic being created against a broker that cannot
     * be reached) before failing them: without a limit they would hold the shutdown up to their own timeout.
     */
    static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private final AdminClient adminClient;
    private final KafkaCustomizations customizations;
    /**
     * Topics found or created, with the {@link #nanoClock} time until which they are taken as existing.
     */
    private final Map<String, Long> existingTopics = new ConcurrentHashMap<>();
    private final boolean checkTopics;
    /**
     * Topics found missing, with the {@link #nanoClock} time until which they are reported missing.
     */
    private final Map<String, Long> missingTopics = new ConcurrentHashMap<>();
    /**
     * The topics being described, so that the sends that wait for the same topic share one request.
     */
    private final Map<String, Mono<Void>> lookups = new ConcurrentHashMap<>();
    private final Duration missingTopicTtl;
    private final Duration existingTopicTtl;
    private final LongSupplier nanoClock;
    /**
     * Who uses the admin client: it is closed once every one of them closed the creator (see {@link #retain()}).
     */
    private final AtomicInteger owners = new AtomicInteger(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    public TopologyCreator(AdminClient adminClient, KafkaCustomizations customizations, boolean checkTopics) {
        this(adminClient, customizations, checkTopics, MISSING_TOPIC_TTL, EXISTING_TOPIC_TTL, System::nanoTime);
    }

    TopologyCreator(AdminClient adminClient, KafkaCustomizations customizations, boolean checkTopics,
                    Duration missingTopicTtl, LongSupplier nanoClock) {
        this(adminClient, customizations, checkTopics, missingTopicTtl, EXISTING_TOPIC_TTL, nanoClock);
    }

    TopologyCreator(AdminClient adminClient, KafkaCustomizations customizations, boolean checkTopics,
                    Duration missingTopicTtl, Duration existingTopicTtl, LongSupplier nanoClock) {
        this.adminClient = adminClient;
        this.customizations = customizations;
        this.checkTopics = checkTopics;
        this.missingTopicTtl = missingTopicTtl;
        this.existingTopicTtl = existingTopicTtl;
        this.nanoClock = nanoClock;
    }

    /**
     * Lists the topics that exist in the cluster, blocking the calling thread. The library does not call it anymore.
     *
     * @deprecated lists every topic of the cluster, which needs the permission to describe all of them and may be
     * costly; use {@link #ensureTopic(String)}, which describes only the topic it checks
     */
    @Deprecated(forRemoval = true, since = "7.9.0")
    @SneakyThrows
    public Map<String, Boolean> getTopics() {
        if (!checkTopics) {
            return new ConcurrentHashMap<>();
        }
        ListTopicsResult topics = adminClient.listTopics(new ListTopicsOptions().timeoutMs(TIMEOUT_MS));
        return topics.names().get().stream().collect(Collectors.toConcurrentMap(name -> name, name -> true));
    }

    public Mono<Void> createTopics(List<String> topics) {
        return Flux.fromIterable(topics)
                .map(topic -> {
                    if (customizations.getTopics().containsKey(topic)) {
                        return customizations.getTopics().get(topic);
                    }
                    return TopicCustomization.builder()
                            .partitions(-1)
                            .replicationFactor((short) -1)
                            .topic(topic).build();
                })
                .map(this::toNewTopic)
                .flatMap(this::createTopic)
                .doOnNext(topic -> found(topic.name()))
                .then();
    }

    /**
     * Creates a dedicated DLQ topic for each of the given base topics, using the {@code .dlq} suffix convention.
     *
     * @param baseTopics the topics for which a DLQ topic should be created
     * @return a {@link Mono} that completes once every DLQ topic has been created
     */
    public Mono<Void> createDlqTopics(List<String> baseTopics) {
        List<String> dlqTopics = baseTopics.stream()
                .map(TopologyCreator::toDlqTopic)
                .toList();
        return createTopics(dlqTopics);
    }

    protected static String toDlqTopic(String topic) {
        return topic + DLQ_SUFFIX;
    }

    protected Mono<NewTopic> createTopic(NewTopic topic) {
        return Mono.fromFuture(() -> adminClient.createTopics(List.of(topic))
                        .all()
                        .toCompletionStage()
                        .toCompletableFuture())
                .thenReturn(topic)
                .onErrorResume(TopicExistsException.class, e -> Mono.just(topic));
    }

    protected NewTopic toNewTopic(TopicCustomization customization) {
        NewTopic topic = new NewTopic(customization.getTopic(), customization.getPartitions(), customization.getReplicationFactor());
        if (customization.getConfig() != null) {
            return topic.configs(customization.getConfig());
        }
        return topic;
    }

    /**
     * The partitions the topics have now, in order. Fails if a topic does not exist.
     */
    public Mono<List<TopicPartition>> partitionsOf(List<String> topics) {
        return Mono.fromFuture(() -> adminClient.describeTopics(topics, new DescribeTopicsOptions()
                                .timeoutMs(TIMEOUT_MS))
                        .allTopicNames()
                        .toCompletionStage()
                        .toCompletableFuture())
                .map(descriptions -> descriptions.values().stream()
                        .flatMap(description -> description.partitions().stream()
                                .map(partition -> new TopicPartition(description.name(), partition.partition())))
                        .sorted(Comparator.comparing(TopicPartition::topic).thenComparing(TopicPartition::partition))
                        .toList());
    }

    /**
     * Completes when the topic exists, or fails with {@link TopicNotFoundException} when it does not; completes at
     * once when {@code checkTopics} is disabled. It never blocks the subscribing thread: a topic not known yet is
     * described with the admin client, once for all the sends that wait for it.
     * <p>
     * A topic found is remembered for {@link #EXISTING_TOPIC_TTL}, then described again, so a topic deleted meanwhile
     * is reported missing. A topic found missing fails at once, without asking the cluster again, for
     * {@link #MISSING_TOPIC_TTL}: a send to a topic that does not exist does not describe it on every attempt, and a
     * topic created meanwhile by someone else is found once that time passes. Any other error (e.g. a timeout) is not
     * remembered.
     */
    public Mono<Void> ensureTopic(String topic) {
        if (!checkTopics || isKnown(topic)) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
            if (isKnown(topic)) {
                return Mono.empty();
            }
            Long missingUntil = missingTopics.get(topic);
            if (missingUntil != null) {
                if (nanoClock.getAsLong() - missingUntil < 0) {
                    return Mono.error(topicNotFound(topic));
                }
                missingTopics.remove(topic, missingUntil);
            }
            return lookups.computeIfAbsent(topic, this::describe);
        });
    }

    /**
     * Whether the topic was found or created less than {@link #EXISTING_TOPIC_TTL} ago.
     */
    private boolean isKnown(String topic) {
        Long existsUntil = existingTopics.get(topic);
        if (existsUntil == null) {
            return false;
        }
        if (nanoClock.getAsLong() - existsUntil < 0) {
            return true;
        }
        existingTopics.remove(topic, existsUntil);
        return false;
    }

    private void found(String topic) {
        existingTopics.put(topic, nanoClock.getAsLong() + existingTopicTtl.toNanos());
        missingTopics.remove(topic);
    }

    /**
     * Shared by every send that waits for the topic: {@code cache()} keeps describing it even if one of them cancels,
     * and the lookup is forgotten once it ends, its outcome being kept in the caches.
     */
    private Mono<Void> describe(String topic) {
        return Mono.fromFuture(() -> adminClient.describeTopics(List.of(topic), new DescribeTopicsOptions()
                                .timeoutMs(TIMEOUT_MS))
                        .allTopicNames()
                        .toCompletionStage()
                        .toCompletableFuture())
                .doOnNext(descriptions -> found(topic))
                .then()
                .onErrorMap(TopologyCreator::isUnknownTopic, e -> {
                    missingTopics.put(topic, nanoClock.getAsLong() + missingTopicTtl.toNanos());
                    return topicNotFound(topic);
                })
                .doFinally(signal -> lookups.remove(topic))
                .cache();
    }

    private static boolean isUnknownTopic(Throwable error) {
        return error instanceof UnknownTopicOrPartitionException
                || error.getCause() instanceof UnknownTopicOrPartitionException;
    }

    private static TopicNotFoundException topicNotFound(String topic) {
        return new TopicNotFoundException("Topic not found: " + topic + ". Please create it before send a message.");
    }

    /**
     * Checks that the topic exists, describing it (only it) when it is not known yet.
     *
     * @deprecated blocks the calling thread while it describes the topic; use {@link #ensureTopic(String)}
     */
    @Deprecated(forRemoval = true, since = "7.9.0")
    @SneakyThrows
    public void checkTopic(String topicName) {
        if (!checkTopics || isKnown(topicName)) {
            return;
        }
        try {
            adminClient.describeTopics(List.of(topicName), new DescribeTopicsOptions().timeoutMs(TIMEOUT_MS))
                    .allTopicNames()
                    .get();
            found(topicName);
        } catch (ExecutionException e) {
            if (isUnknownTopic(e.getCause())) {
                throw topicNotFound(topicName);
            }
            throw e.getCause();
        }
    }

    /**
     * The admin client of the creator, which other components of its domain (e.g. its health check) may use instead
     * of opening one of their own. It is closed by {@link #close()}.
     */
    public AdminClient adminClient() {
        return adminClient;
    }

    /**
     * Registers one more user of this creator, e.g. another domain that shares its admin client. Each user calls
     * {@link #close()} once it is done; the admin client is closed by the last one.
     *
     * @return {@code false} when the creator is already closed and cannot be used anymore
     */
    public boolean retain() {
        return owners.getAndUpdate(count -> count == 0 ? 0 : count + 1) > 0;
    }

    /**
     * Closes the admin client used to describe and create the topics, once every user of the creator closed it (see
     * {@link #retain()}). The requests still pending are failed after {@link #CLOSE_TIMEOUT}. Idempotent.
     */
    public void close() {
        if (owners.updateAndGet(count -> Math.max(0, count - 1)) == 0 && closed.compareAndSet(false, true)) {
            adminClient.close(CLOSE_TIMEOUT);
        }
    }
}
