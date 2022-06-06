package org.reactivecommons.async.impl.sns.communications;

import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.*;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.HashMap;
import java.util.Map;

@Log4j2
@AllArgsConstructor
public class TopologyCreator {

    private final SnsAsyncClient topicClient;
    private final SqsAsyncClient queueClient;


    public Mono<String> declareTopic(String name) {
        return listTopics()
                .map((topic) -> topic.topicArn())
                .filter((topic) -> topic.contains(":" + name))
                .switchIfEmpty(createTopic(name))
                .single();
    }

    private Mono<ListTopicsRequest> getListTopicRequest() {
        return Mono.just(ListTopicsRequest.builder().build());
    }

    public Flux<Topic> listTopics() {
        return getListTopicRequest()
                .flatMap(request -> Mono.fromFuture(topicClient.listTopics(request)))
                .flatMapMany((response) -> Flux.fromIterable(response.topics()));
    }

    private Mono<CreateTopicRequest> getCreateTopicRequest(String name) {
        return Mono.just(CreateTopicRequest.builder().name(name).build());
    }

    public Mono<String> createTopic(String name) {
        return getCreateTopicRequest(name)
                .flatMap(request -> Mono.fromFuture(topicClient.createTopic(request)))
                .map(CreateTopicResponse::topicArn)
                .doOnNext((response) -> log.debug("Topic Created: " + response))
                .doOnError((e) -> log.error("Error creating topic: " + e.toString()));
    }


    private Mono<CreateQueueRequest> createQueueRequest(String name) {
        return Mono.just(CreateQueueRequest.builder().queueName(name).build());
    }

    public Mono<String> createQueue(String name) {
        return createQueueRequest(name)
                .flatMap(request -> Mono.fromFuture(queueClient.createQueue(request)))
                .map(CreateQueueResponse::queueUrl)
                .doOnNext((response) -> log.debug("Queue created: " + response))
                .doOnError((e) -> log.error("Error creating queue: " + e.toString()));
    }


    public Mono<String> getTopicArn(String name) {
        return listTopics()
                .map(Topic::topicArn)
                .filter((topic) -> topic.contains(":" + name))
                .single();
    }

    private Mono<GetQueueUrlRequest> getQueueUrlRequest(String queueName) {
        return Mono.just(GetQueueUrlRequest.builder().queueName(queueName).build());
    }

    public Mono<String> getQueueUrl(String name) {
        return getQueueUrlRequest(name)
                .flatMap((request) -> Mono.fromFuture(queueClient.getQueueUrl(request)))
                .map(GetQueueUrlResponse::queueUrl);
    }

    public Mono<String> bind(String queueName, String topicName) {
        return getQueueUrl(queueName)
                .flatMap(this::getQueueArn)
                .zipWith(getTopicArn(topicName))
                .flatMap((a) -> getSubscribeRequest(a.getT1(), a.getT2()))
                .flatMap(request -> Mono.fromFuture(topicClient.subscribe(request)))
                .map(SubscribeResponse::subscriptionArn)
                .onErrorMap(TopologyDefException::new);
    }


    private Mono<SubscribeRequest> getSubscribeRequest(String queueArn, String topicArn) {
        SubscribeRequest subscribeRequest = SubscribeRequest.builder()
                .protocol("sqs")
                .endpoint(queueArn)
                .topicArn(topicArn)
                .returnSubscriptionArn(true)
                .build();
        return Mono.just(subscribeRequest);
    }

    private Mono<GetQueueAttributesRequest> getQueueAttributesRequest(String queueUrl) {
        GetQueueAttributesRequest subscribeRequest = GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNamesWithStrings("All")
                .build();
        return Mono.just(subscribeRequest);
    }

    public Mono<String> getQueueArn(String queueUrl) {
        return getQueueAttributesRequest(queueUrl)
                .flatMap(request -> Mono.fromFuture(queueClient.getQueueAttributes(request)))
                .map((response) -> response.attributesAsStrings().get("QueueArn"));

    }

    public Mono<String> setQueueAttributes(String queueName, String topicName, String arnSnsPrefix,
                                           String arnSqsPrefix) {
        return getQueueUrl(queueName)
                .flatMap(queueUrl -> {
                    Map<String, String> attributes = getAttributeMap(queueName, topicName, arnSnsPrefix, arnSqsPrefix);
                    return setQueueAttributesRequest(queueUrl, attributes);
                })
                .flatMap(request -> Mono.fromFuture(queueClient.setQueueAttributes(request)))
                .map(SetQueueAttributesResponse::toString);

    }

    private Map<String, String> getAttributeMap(String queueName, String topicName, String arnSnsPrefix,
                                                String arnSqsPrefix) {
        Map<String, String> map = new HashMap<>();
        map.put("Policy", "{\n" +
                "  \"Version\": \"2012-10-17\",\n" +
                "  \"Id\": \"" + arnSqsPrefix + ":" + queueName + "/SQSDefaultPolicy\",\n" +
                "  \"Statement\": [\n" +
                "    {\n" +
                "      \"Sid\": \"topic-subscription-" + arnSnsPrefix + ":" + topicName + "\",\n" +
                "      \"Effect\": \"Allow\",\n" +
                "      \"Principal\": {\n" +
                "        \"AWS\": \"*\"\n" +
                "      },\n" +
                "      \"Action\": \"SQS:SendMessage\",\n" +
                "      \"Resource\": \"" + arnSqsPrefix + ":" + queueName + "\",\n" +
                "      \"Condition\": {\n" +
                "        \"ArnLike\": {\n" +
                "          \"aws:SourceArn\": \"" + arnSnsPrefix + ":" + topicName + "\"\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}");
        return map;
    }


    private Mono<SetQueueAttributesRequest> setQueueAttributesRequest(String queueUrl, Map<String, String> attributes) {
        SetQueueAttributesRequest setQueueAttributesRequest = SetQueueAttributesRequest.builder().queueUrl(queueUrl)
                .attributesWithStrings(attributes)
                .build();
        return Mono.just(setQueueAttributesRequest);
    }


    public static class TopologyDefException extends RuntimeException {
        public TopologyDefException(Throwable cause) {
            super(cause);
        }
    }
}
