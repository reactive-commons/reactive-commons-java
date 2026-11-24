package org.reactivecommons.async.kafka.communications.topology;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
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

import java.time.Duration;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
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
    @SuppressWarnings("removal")
    void shouldCheckATopicByDescribingOnlyThatTopic() {
        // Arrange
        describeAnswers(found());
        creator = new TopologyCreator(adminClient, customizations, true);
        // Act
        creator.checkTopic("topic1");
        // Assert
        verify(adminClient).describeTopics(eq(List.of("topic1")), any(DescribeTopicsOptions.class));
        verify(adminClient, never()).listTopics(any(ListTopicsOptions.class));
    }

    @Test
    @SuppressWarnings("removal")
    void shouldFailWhenCheckTopics() {
        // Arrange
        describeAnswers(failed(new UnknownTopicOrPartitionException("unknown")));
        creator = new TopologyCreator(adminClient, customizations, true);
        // Assert
        assertThrows(TopicNotFoundException.class, () ->
                // Act
                creator.checkTopic("topic3"));
    }

    @Test
    @SuppressWarnings("removal")
    void shouldNotDescribeATopicAlreadyFound() {
        // Arrange
        describeAnswers(found());
        creator = new TopologyCreator(adminClient, customizations, true);

        // Act
        creator.checkTopic("topic1");
        creator.checkTopic("topic1");

        // Assert
        verify(adminClient, times(1)).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCreateDlqTopicsWithDlqSuffix() {
        // Arrange
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
    @SuppressWarnings("removal")
    void shouldRegisterCreatedDlqTopicsSoTheyCanBeSentTo() {
        // Arrange
        KafkaFutureImpl<Void> create = new KafkaFutureImpl<>();
        create.complete(null);
        doReturn(create).when(createTopicsResult).all();
        when(adminClient.createTopics(any())).thenReturn(createTopicsResult);
        creator = new TopologyCreator(adminClient, customizations, true);

        // Act
        StepVerifier.create(creator.createDlqTopics(List.of("topic1"))).verifyComplete();

        // Assert: the topic is known, the cluster is not asked about it
        assertDoesNotThrow(() -> creator.checkTopic("topic1.dlq"));
        StepVerifier.create(creator.ensureTopic("topic1.dlq")).verifyComplete();
        verify(adminClient, never()).describeTopics(anyCollection(), any(DescribeTopicsOptions.class));
    }

    @Test
    @SuppressWarnings("removal")
    void shouldTreatAlreadyExistingDlqTopicAsCreated() {
        // Arrange: another replica already created the DLQ topic
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
    @SuppressWarnings("unchecked")
    void shouldApplyCustomizationsToDlqTopicsByTheirOwnName() {
        // Arrange
        KafkaFutureImpl<Void> create = new KafkaFutureImpl<>();
        create.complete(null);
        doReturn(create).when(createTopicsResult).all();
        when(adminClient.createTopics(any())).thenReturn(createTopicsResult);
        var dlqCustomization = new TopicCustomization("topic2.dlq", 2, (short) 3, null);
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
    void shouldCloseTheAdminClientOnceWithABoundedWait() {
        creator = new TopologyCreator(adminClient, customizations, true);

        creator.close();
        creator.close();

        verify(adminClient, times(1)).close(TopologyCreator.CLOSE_TIMEOUT);
    }

    /**
     * KC-31: the admin client of a domain is shared (e.g. with the discard notifier): it is closed once every user
     * closed the creator.
     */
    @Test
    void shouldCloseASharedAdminClientOnceTheLastUserClosesIt() {
        creator = new TopologyCreator(adminClient, customizations, true);
        assertThat(creator.retain()).isTrue();

        creator.close();
        verify(adminClient, never()).close(any(Duration.class));

        creator.close();
        verify(adminClient, times(1)).close(TopologyCreator.CLOSE_TIMEOUT);
        assertThat(creator.retain()).as("a closed creator cannot be shared anymore").isFalse();
    }

    @Test
    void toDlqTopicShouldAppendDlqSuffix() {
        assertThat(TopologyCreator.toDlqTopic("my-topic")).isEqualTo("my-topic.dlq");
    }

    private void describeAnswers(KafkaFutureImpl<Map<String, TopicDescription>> future) {
        DescribeTopicsResult result = mock(DescribeTopicsResult.class);
        doReturn(future).when(result).allTopicNames();
        when(adminClient.describeTopics(anyCollection(), any(DescribeTopicsOptions.class))).thenReturn(result);
    }

    private static KafkaFutureImpl<Map<String, TopicDescription>> found() {
        KafkaFutureImpl<Map<String, TopicDescription>> future = new KafkaFutureImpl<>();
        future.complete(Map.of());
        return future;
    }

    private static KafkaFutureImpl<Map<String, TopicDescription>> failed(Throwable error) {
        KafkaFutureImpl<Map<String, TopicDescription>> future = new KafkaFutureImpl<>();
        future.completeExceptionally(error);
        return future;
    }
}
