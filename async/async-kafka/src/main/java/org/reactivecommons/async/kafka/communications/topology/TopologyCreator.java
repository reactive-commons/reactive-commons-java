package org.reactivecommons.async.kafka.communications.topology;

import lombok.SneakyThrows;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.reactivecommons.async.kafka.communications.exceptions.TopicNotFoundException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TopologyCreator {
    public static final int TIMEOUT_MS = 60_000;
    public static final String DLQ_SUFFIX = ".dlq";
    private final AdminClient adminClient;
    private final KafkaCustomizations customizations;
    private final Map<String, Boolean> existingTopics;
    private final boolean checkTopics;

    public TopologyCreator(AdminClient adminClient, KafkaCustomizations customizations, boolean checkTopics) {
        this.adminClient = adminClient;
        this.customizations = customizations;
        this.checkTopics = checkTopics;
        this.existingTopics = getTopics();
    }

    /**
     * Lists the topics that exist in the cluster. The returned map is always mutable, because created topics are
     * registered on it (see {@link #createTopics(List)}), even when {@code checkTopics} is disabled.
     */
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
                .doOnNext(topic -> existingTopics.put(topic.name(), true))
                .then();
    }

    /**
     * Creates a dedicated DLQ topic for each of the given base topics, using the {@code .dlq} suffix convention
     * (e.g. {@code my-topic} -&gt; {@code my-topic.dlq}). DLQ topics follow the same customization rules
     * (via {@link KafkaCustomizations}) as regular topics, matched by their own (suffixed) name.
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
        return Mono.fromFuture(adminClient.createTopics(List.of(topic))
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

    public void checkTopic(String topicName) {
        if (checkTopics && !existingTopics.containsKey(topicName)) {
            existingTopics.putAll(getTopics());
            if (!existingTopics.containsKey(topicName)) {
                throw new TopicNotFoundException("Topic not found: " + topicName +
                        ". Please create it before send a message.");
            }
        }
    }
}
