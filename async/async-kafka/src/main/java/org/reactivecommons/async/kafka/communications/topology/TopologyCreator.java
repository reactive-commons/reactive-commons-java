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
import java.util.stream.Collectors;

public class TopologyCreator {
    public static final int TIMEOUT_MS = 60_000;
    private final AdminClient adminClient;
    private final KafkaCustomizations customizations;
    private final Map<String, Boolean> existingTopics;

    public TopologyCreator(AdminClient adminClient, KafkaCustomizations customizations) {
        this.adminClient = adminClient;
        this.customizations = customizations;
        this.existingTopics = getTopics();
    }

    @SneakyThrows
    public Map<String, Boolean> getTopics() {
        ListTopicsResult topics = adminClient.listTopics(new ListTopicsOptions().timeoutMs(TIMEOUT_MS));
        return topics.names().get().stream().collect(Collectors.toConcurrentMap(name -> name, name -> true));
    }

    public Mono<Void> createTopics(List<String> topics) {
        TopicCustomization.TopicCustomizationBuilder defaultBuilder = TopicCustomization.builder()
                .partitions(-1)
                .replicationFactor((short) -1);

        return Flux.fromIterable(topics)
                .map(topic -> {
                    if (customizations.getTopics().containsKey(topic)) {
                        return customizations.getTopics().get(topic);
                    }
                    return defaultBuilder.topic(topic).build();
                })
                .map(this::toNewTopic)
                .flatMap(this::createTopic)
                .doOnNext(topic -> existingTopics.put(topic.name(), true))
                .then();
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
        if (!existingTopics.containsKey(topicName)) {
            throw new TopicNotFoundException("Topic not found: " + topicName + ". Please create it before send a message.");
            // TODO: should refresh topics?? getTopics();
        }
    }
}
