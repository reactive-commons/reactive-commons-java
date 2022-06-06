package org.reactivecommons.async.impl.sns.communications;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.*;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class TopologyCreatorTest {
    @Mock
    private SnsAsyncClient topicClient;
    @Mock
    private SqsAsyncClient queueClient;

    private TopologyCreator creator;

    @Before
    public void setup() {
        creator = new TopologyCreator(topicClient, queueClient);
    }

    @Test
    public void shouldListTopics() {
        // Arrange
        String name = "topic-name";
        String expectedTopicArn = buildTopicArn(name);
        mockListTopics(name);
        // Act
        Flux<Topic> topics = creator.listTopics();
        // Assert
        StepVerifier.create(topics)
                .assertNext(topic -> assertThat(topic.topicArn()).isEqualTo(expectedTopicArn))
                .verifyComplete();
    }

    @Test
    public void shouldCreateTopic() {
        // Arrange
        String name = "test";
        String expectedArn = buildTopicArn(name);
        mockCreateTopic(name);
        ArgumentCaptor<CreateTopicRequest> captor = ArgumentCaptor.forClass(CreateTopicRequest.class);
        // Act
        Mono<String> topic = creator.createTopic(name);
        // Assert
        StepVerifier.create(topic)
                .assertNext(topicArn -> assertThat(topicArn).isEqualTo(expectedArn))
                .verifyComplete();
        verify(topicClient).createTopic(captor.capture());
        CreateTopicRequest request = captor.getValue();
        assertThat(request.name()).isEqualTo(name);
    }

    @Test
    public void shouldDeclareTopic() {
        // Arrange
        String name = "new-topic-name";
        String expectedArn = buildTopicArn(name);
        mockListTopics("existing-topic-name");
        mockCreateTopic(name);
        ArgumentCaptor<CreateTopicRequest> captor = ArgumentCaptor.forClass(CreateTopicRequest.class);
        // Act
        Mono<String> topic = creator.declareTopic(name);
        // Assert
        StepVerifier.create(topic)
                .assertNext(topicArn -> assertThat(topicArn).isEqualTo(expectedArn))
                .verifyComplete();
        verify(topicClient).createTopic(captor.capture());
        CreateTopicRequest request = captor.getValue();
        assertThat(request.name()).isEqualTo(name);
    }

    @Test
    public void shouldNotDeclareTopicWhenExists() {
        // Arrange
        String name = "topic-name";
        String expectedArn = buildTopicArn(name);
        mockListTopics(name);
        mockCreateTopic(name);
        // Act
        Mono<String> topic = creator.declareTopic(name);
        // Assert
        StepVerifier.create(topic)
                .assertNext(topicArn -> assertThat(topicArn).isEqualTo(expectedArn))
                .verifyComplete();
        verify(topicClient, times(0)).createTopic(any(CreateTopicRequest.class));
    }

    @Test
    public void shouldGetTopicArn() {
        // Arrange
        String name = "topic-name";
        String expectedArn = buildTopicArn(name);
        mockListTopics(name);
        // Act
        Mono<String> topic = creator.getTopicArn(name);
        // Assert
        StepVerifier.create(topic)
                .assertNext(topicArn -> assertThat(topicArn).isEqualTo(expectedArn))
                .verifyComplete();
    }

    @Test
    public void shouldCreateQueue() {
        // Arrange
        String name = "queue-name";
        String expectedUrl = buildQueueUrl(name);
        mockCreateQueue(name);
        ArgumentCaptor<CreateQueueRequest> captor = ArgumentCaptor.forClass(CreateQueueRequest.class);
        // Act
        Mono<String> queue = creator.createQueue(name);
        // Assert
        StepVerifier.create(queue)
                .assertNext(queueUrl -> assertThat(queueUrl).isEqualTo(expectedUrl))
                .verifyComplete();
        verify(queueClient).createQueue(captor.capture());
        CreateQueueRequest request = captor.getValue();
        assertThat(request.queueName()).isEqualTo(name);
    }

    @Test
    public void shouldGetQueueUrl() {
        // Arrange
        String name = "queue-name";
        String expectedUrl = buildQueueUrl(name);
        mockGetQueueUrl(name);
        ArgumentCaptor<GetQueueUrlRequest> captor = ArgumentCaptor.forClass(GetQueueUrlRequest.class);
        // Act
        Mono<String> queue = creator.getQueueUrl(name);
        // Assert
        StepVerifier.create(queue)
                .assertNext(queueUrl -> assertThat(queueUrl).isEqualTo(expectedUrl))
                .verifyComplete();
        verify(queueClient).getQueueUrl(captor.capture());
        GetQueueUrlRequest request = captor.getValue();
        assertThat(request.queueName()).isEqualTo(name);
    }

    @Test
    public void shouldGetQueueAttributes() {
        // Arrange
        String name = "queue-name";
        String queueUrl = buildQueueUrl(name);
        String expectedArn = buildQueueArn(name);
        mockGetQueueAttributes(name);
        ArgumentCaptor<GetQueueAttributesRequest> captor = ArgumentCaptor.forClass(GetQueueAttributesRequest.class);
        // Act
        Mono<String> queue = creator.getQueueArn(queueUrl);
        // Assert
        StepVerifier.create(queue)
                .assertNext(queueArn -> assertThat(queueArn).isEqualTo(expectedArn))
                .verifyComplete();
        verify(queueClient).getQueueAttributes(captor.capture());
        GetQueueAttributesRequest request = captor.getValue();
        assertThat(request.queueUrl()).isEqualTo(queueUrl);
    }

    @Test
    public void shouldSetQueueAttributes() {
        // Arrange
        String sqsPrefix = "sqs-prefix";
        String queueName = "queue-name";
        String snsPrefix = "sns-prefix";
        String topicName = "topic-name";
        String expectedResult = "SetQueueAttributesResponse()";
        mockGetQueueUrl(queueName);
        mockSetQueueAttributes();
        ArgumentCaptor<SetQueueAttributesRequest> captor = ArgumentCaptor.forClass(SetQueueAttributesRequest.class);
        // Act
        Mono<String> queue = creator.setQueueAttributes(queueName, topicName, snsPrefix, sqsPrefix);
        // Assert
        StepVerifier.create(queue)
                .assertNext(queueArn -> assertThat(queueArn).isEqualTo(expectedResult))
                .verifyComplete();
        verify(queueClient).setQueueAttributes(captor.capture());
        SetQueueAttributesRequest request = captor.getValue();
        assertThat(request.queueUrl()).isEqualTo(buildQueueUrl(queueName));
        assertThat(request.attributesAsStrings().get("Policy")).isEqualTo(expectedPolicy());
    }

    @Test
    public void shouldBind() {
        // Arrange
        String queueName = "queue-name";
        String topicName = "topic-name";
        String expectedArn = buildSubscriptionArn(topicName, queueName);
        mockGetQueueUrl(queueName);
        mockGetQueueAttributes(queueName);
        mockListTopics(topicName);
        mockTopicSubscription(topicName, queueName);
        ArgumentCaptor<SubscribeRequest> captor = ArgumentCaptor.forClass(SubscribeRequest.class);
        // Act
        Mono<String> subscription = creator.bind(queueName, topicName);
        // Assert
        StepVerifier.create(subscription)
                .assertNext(arn -> assertThat(arn).isEqualTo(expectedArn))
                .verifyComplete();
        verify(topicClient).subscribe(captor.capture());
        SubscribeRequest request = captor.getValue();
        assertThat(request.protocol()).isEqualTo("sqs");
        assertThat(request.endpoint()).isEqualTo(buildQueueArn(queueName));
        assertThat(request.topicArn()).isEqualTo(buildTopicArn(topicName));
        assertThat(request.returnSubscriptionArn()).isTrue();
    }

    private void mockListTopics(String name) {
        ListTopicsResponse response = ListTopicsResponse.builder()
                .topics(Topic.builder().topicArn(buildTopicArn(name)).build())
                .build();
        when(topicClient.listTopics(any(ListTopicsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private void mockCreateTopic(String name) {
        CreateTopicResponse response = CreateTopicResponse.builder().topicArn(buildTopicArn(name)).build();
        when(topicClient.createTopic(any(CreateTopicRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private void mockTopicSubscription(String name, String queueName) {
        SubscribeResponse response = SubscribeResponse.builder()
                .subscriptionArn(buildSubscriptionArn(name, queueName))
                .build();
        when(topicClient.subscribe(any(SubscribeRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private void mockCreateQueue(String name) {
        CreateQueueResponse response = CreateQueueResponse.builder().queueUrl(buildQueueUrl(name)).build();
        when(queueClient.createQueue(any(CreateQueueRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private void mockGetQueueUrl(String name) {
        GetQueueUrlResponse response = GetQueueUrlResponse.builder().queueUrl(buildQueueUrl(name)).build();
        when(queueClient.getQueueUrl(any(GetQueueUrlRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private void mockGetQueueAttributes(String name) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("QueueArn", buildQueueArn(name));
        GetQueueAttributesResponse response = GetQueueAttributesResponse.builder()
                .attributesWithStrings(attributes)
                .build();
        when(queueClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private void mockSetQueueAttributes() {
        SetQueueAttributesResponse response = SetQueueAttributesResponse.builder().build();
        when(queueClient.setQueueAttributes(any(SetQueueAttributesRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
    }

    private String buildTopicArn(String name) {
        return "arn:aws:sns:us-east-1:123456789012:" + name;
    }

    private String buildQueueArn(String name) {
        return "arn:aws:sqs:us-east-1:123456789012::" + name;
    }

    private String buildQueueUrl(String name) {
        return "https://queue.amazonaws.com/123456789012/" + name;
    }

    private String buildSubscriptionArn(String topicName, String id) {
        return buildTopicArn(topicName) + ":" + id;
    }

    private String expectedPolicy() {
        return "{\n" +
                "  \"Version\": \"2012-10-17\",\n" +
                "  \"Id\": \"sqs-prefix:queue-name/SQSDefaultPolicy\",\n" +
                "  \"Statement\": [\n" +
                "    {\n" +
                "      \"Sid\": \"topic-subscription-sns-prefix:topic-name\",\n" +
                "      \"Effect\": \"Allow\",\n" +
                "      \"Principal\": {\n" +
                "        \"AWS\": \"*\"\n" +
                "      },\n" +
                "      \"Action\": \"SQS:SendMessage\",\n" +
                "      \"Resource\": \"sqs-prefix:queue-name\",\n" +
                "      \"Condition\": {\n" +
                "        \"ArnLike\": {\n" +
                "          \"aws:SourceArn\": \"sns-prefix:topic-name\"\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }
}
