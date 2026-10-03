package org.reactivecommons.async.kafka.communications.topology;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivecommons.async.kafka.communications.exceptions.TopicNotFoundException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopologyCreatorTest {

    private TopologyCreator creator;
    @Mock
    private AdminClient adminClient;
    @Mock
    private ListTopicsResult listTopicsResult;
    @Mock
    private CreateTopicsResult createTopicsResult;
    private KafkaCustomizations customizations;

    @BeforeEach
    void setUp() {
        Map<String, String> config = new HashMap<>();
        config.put("cleanup.policy", "compact");
        TopicCustomization customization = new TopicCustomization("topic1", 3, (short) 1, config);
        customizations = KafkaCustomizations.withTopic("topic1", customization);
    }

    @Test
    void shouldCreateTopics() {
        // Arrange
        KafkaFutureImpl<Set<String>> names = new KafkaFutureImpl<>();
        names.complete(Set.of("topic1", "topic2"));
        doReturn(names).when(listTopicsResult).names();
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);

        KafkaFutureImpl<Void> create = new KafkaFutureImpl<>();
        create.complete(null);
        doReturn(create).when(createTopicsResult).all();
        when(adminClient.createTopics(any())).thenReturn(createTopicsResult);
        creator = new TopologyCreator(adminClient, customizations, true);
        // Act
        Mono<Void> flow = creator.createTopics(List.of("topic1", "topic2"));
        // Assert
        StepVerifier.create(flow)
                .verifyComplete();
    }

    @Test
    void shouldCheckTopics() {
        // Arrange
        KafkaFutureImpl<Set<String>> names = new KafkaFutureImpl<>();
        names.complete(Set.of("topic1", "topic2"));
        doReturn(names).when(listTopicsResult).names();
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);
        creator = new TopologyCreator(adminClient, customizations, true);
        // Act
        creator.checkTopic("topic1");
        // Assert
        verify(listTopicsResult, times(1)).names();
    }

    @Test
    void shouldFailWhenCheckTopics() {
        // Arrange
        KafkaFutureImpl<Set<String>> names = new KafkaFutureImpl<>();
        names.complete(Set.of("topic1", "topic2"));
        doReturn(names).when(listTopicsResult).names();
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);
        creator = new TopologyCreator(adminClient, customizations, true);
        // Assert
        assertThrows(TopicNotFoundException.class, () ->
                // Act
                creator.checkTopic("topic3"));
    }

    @Test
    void shouldRefreshTopicsBeforeFailingWhenTopicIsMissingFromCache() {
        // Arrange: topic3 does not exist yet when the creator is built...
        KafkaFutureImpl<Set<String>> initialNames = new KafkaFutureImpl<>();
        initialNames.complete(Set.of("topic1", "topic2"));
        KafkaFutureImpl<Set<String>> refreshedNames = new KafkaFutureImpl<>();
        refreshedNames.complete(Set.of("topic1", "topic2", "topic3"));
        ListTopicsResult refreshedListTopicsResult = mock(ListTopicsResult.class);
        doReturn(initialNames).when(listTopicsResult).names();
        doReturn(refreshedNames).when(refreshedListTopicsResult).names();
        // ... but it is created by another process/replica after the cache was initialized
        when(adminClient.listTopics(any(ListTopicsOptions.class)))
                .thenReturn(listTopicsResult)
                .thenReturn(refreshedListTopicsResult);
        creator = new TopologyCreator(adminClient, customizations, true);

        // Act: checkTopic should transparently refresh the cache and find it, instead of failing immediately
        creator.checkTopic("topic3");

        // Assert
        verify(adminClient, times(2)).listTopics(any(ListTopicsOptions.class));
    }

    @Test
    void shouldStillFailWhenTopicIsMissingAfterRefresh() {
        // Arrange: topic3 never exists, neither at construction time nor after refreshing
        KafkaFutureImpl<Set<String>> names = new KafkaFutureImpl<>();
        names.complete(Set.of("topic1", "topic2"));
        doReturn(names).when(listTopicsResult).names();
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);
        creator = new TopologyCreator(adminClient, customizations, true);

        // Act & Assert
        assertThrows(TopicNotFoundException.class, () -> creator.checkTopic("topic3"));
        // The cache was refreshed once (constructor) + once more inside checkTopic before failing
        verify(adminClient, times(2)).listTopics(any(ListTopicsOptions.class));
    }

    @Test
    void shouldNotRefreshTopicsWhenTopicIsAlreadyCached() {
        // Arrange
        KafkaFutureImpl<Set<String>> names = new KafkaFutureImpl<>();
        names.complete(Set.of("topic1", "topic2"));
        doReturn(names).when(listTopicsResult).names();
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);
        creator = new TopologyCreator(adminClient, customizations, true);

        // Act
        creator.checkTopic("topic1");

        // Assert: only the constructor's initial load, no refresh needed
        verify(adminClient, times(1)).listTopics(any(ListTopicsOptions.class));
    }

    @Test
    void shouldCreateDlqTopicsWithDlqSuffix() {
        // Arrange
        KafkaFutureImpl<Set<String>> names = new KafkaFutureImpl<>();
        names.complete(Set.of());
        doReturn(names).when(listTopicsResult).names();
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);

        KafkaFutureImpl<Void> create = new KafkaFutureImpl<>();
        create.complete(null);
        doReturn(create).when(createTopicsResult).all();
        when(adminClient.createTopics(any())).thenReturn(createTopicsResult);
        creator = new TopologyCreator(adminClient, customizations, true);

        // Act
        Mono<Void> flow = creator.createDlqTopics(List.of("topic1", "topic2"));

        // Assert
        StepVerifier.create(flow).verifyComplete();
        ArgumentCaptor<Collection<NewTopic>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(adminClient, times(2)).createTopics(captor.capture());
        Set<String> createdTopicNames = captor.getAllValues().stream()
                .flatMap(Collection::stream)
                .map(NewTopic::name)
                .collect(Collectors.toSet());
        assertThat(createdTopicNames).containsExactlyInAnyOrder("topic1.dlq", "topic2.dlq");
    }

    @Test
    void shouldCreateTopicsWhenCheckTopicsIsDisabled() {
        KafkaFutureImpl<Void> create = new KafkaFutureImpl<>();
        create.complete(null);
        doReturn(create).when(createTopicsResult).all();
        when(adminClient.createTopics(any())).thenReturn(createTopicsResult);
        creator = new TopologyCreator(adminClient, customizations, false);

        // Act
        Mono<Void> flow = creator.createTopics(List.of("topic1"))
                .then(creator.createDlqTopics(List.of("topic1")));

        // Assert
        StepVerifier.create(flow).verifyComplete();
        verify(adminClient, times(2)).createTopics(any());
        verify(adminClient, never()).listTopics(any(ListTopicsOptions.class));
    }

    @Test
    void shouldRegisterCreatedDlqTopicsSoTheyCanBeSentTo() {
        // Arrange: the DLQ topic does not exist when the creator is built
        KafkaFutureImpl<Set<String>> names = new KafkaFutureImpl<>();
        names.complete(Set.of("topic1"));
        doReturn(names).when(listTopicsResult).names();
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);
        KafkaFutureImpl<Void> create = new KafkaFutureImpl<>();
        create.complete(null);
        doReturn(create).when(createTopicsResult).all();
        when(adminClient.createTopics(any())).thenReturn(createTopicsResult);
        creator = new TopologyCreator(adminClient, customizations, true);

        // Act
        StepVerifier.create(creator.createDlqTopics(List.of("topic1"))).verifyComplete();

        // Assert: checkTopic finds it in the cache, without listing the topics again
        assertDoesNotThrow(() -> creator.checkTopic("topic1.dlq"));
        verify(adminClient, times(1)).listTopics(any(ListTopicsOptions.class));
    }

    @Test
    void shouldTreatAlreadyExistingDlqTopicAsCreated() {
        // Arrange: another replica already created the DLQ topic
        KafkaFutureImpl<Set<String>> names = new KafkaFutureImpl<>();
        names.complete(Set.of("topic1"));
        doReturn(names).when(listTopicsResult).names();
        when(adminClient.listTopics(any(ListTopicsOptions.class))).thenReturn(listTopicsResult);
        KafkaFutureImpl<Void> create = new KafkaFutureImpl<>();
        create.completeExceptionally(new TopicExistsException("Topic 'topic1.dlq' already exists."));
        doReturn(create).when(createTopicsResult).all();
        when(adminClient.createTopics(any())).thenReturn(createTopicsResult);
        creator = new TopologyCreator(adminClient, customizations, true);

        // Act & Assert
        StepVerifier.create(creator.createDlqTopics(List.of("topic1"))).verifyComplete();
        assertDoesNotThrow(() -> creator.checkTopic("topic1.dlq"));
    }

    @Test
    void shouldApplyCustomizationsToDlqTopicsByTheirOwnName() {
        // Arrange
        KafkaFutureImpl<Void> create = new KafkaFutureImpl<>();
        create.complete(null);
        doReturn(create).when(createTopicsResult).all();
        when(adminClient.createTopics(any())).thenReturn(createTopicsResult);
        TopicCustomization dlqCustomization = new TopicCustomization("topic2.dlq", 2, (short) 3, null);
        creator = new TopologyCreator(adminClient, KafkaCustomizations.withTopic("topic2.dlq", dlqCustomization),
                false);

        // Act
        StepVerifier.create(creator.createDlqTopics(List.of("topic2"))).verifyComplete();

        // Assert
        ArgumentCaptor<Collection<NewTopic>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(adminClient).createTopics(captor.capture());
        NewTopic created = captor.getValue().iterator().next();
        assertThat(created.name()).isEqualTo("topic2.dlq");
        assertThat(created.numPartitions()).isEqualTo(2);
        assertThat(created.replicationFactor()).isEqualTo((short) 3);
    }

    @Test
    void toDlqTopicShouldAppendDlqSuffix() {
        assertThat(TopologyCreator.toDlqTopic("my-topic")).isEqualTo("my-topic.dlq");
    }
}
