package org.reactivecommons.async.impl.sns.communications;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
@Log4j2
public class SQSSender {
    private final SqsAsyncClient client;

    public <T> Mono<Void> send(String message, int delaySeconds, String retryCount, String queueUrl) {
        return getSendMessageRequest(message, delaySeconds, retryCount, queueUrl)
                .flatMap( request -> Mono.fromFuture( client.sendMessage(request) ))
                .doOnSuccess(response -> log.info("Retry messange sent " + response.messageId()))
                .doOnError(error -> log.error("Retry message error" + error.getMessage()))
                .then();
    }

    private Mono<SendMessageRequest> getSendMessageRequest(String message, int delaySeconds, String retryCount, String queueUrl) {
        SendMessageRequest request = SendMessageRequest.builder()
                .delaySeconds(delaySeconds)
                .messageBody( message )
                .queueUrl(queueUrl)
                .messageAttributes( incrementRetryAttribute( retryCount ))
                .build();

        return Mono.just(request);
    }

    private Map<String, MessageAttributeValue> incrementRetryAttribute(String retryCount) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        int newCount = Integer.parseInt(retryCount);
        newCount += 1;
        addAttribute(attributes, "retries", newCount + "");
        return attributes;
    }

    private void addAttribute(Map<String, MessageAttributeValue> messageAttributes, final String attributeName, final String attributeValue) {
        MessageAttributeValue messageAttributeValue = MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(attributeValue)
                .build();

        messageAttributes.put(attributeName, messageAttributeValue);
    }

}
