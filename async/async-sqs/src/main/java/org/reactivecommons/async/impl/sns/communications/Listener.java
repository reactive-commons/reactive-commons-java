package org.reactivecommons.async.impl.sns.communications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import lombok.extern.log4j.Log4j2;
import org.reactivecommons.async.api.handlers.GenericHandler;
import org.reactivecommons.async.impl.model.SNSEventModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.Map;

@RequiredArgsConstructor
@Log4j2
@Builder
public class Listener {

    private final SqsAsyncClient client;
    private final SQSSender sqsSender;
    private final int retryDelay;
    private final int maxRetries;
    private final int prefetchCount;

    public Mono<Void> listen(String queueName, GenericHandler<Void, SNSEventModel> handler) {
        return getMessages(queueName)
                .flatMap(this::mapObject)
                .flatMap(tuple -> handleMessage(tuple, queueName, handler))
                .then();
    }

    public Mono<Void> handleMessage(Tuple2<SNSEventModel, Message> tuple,
                                    String queueName,
                                    GenericHandler<Void, SNSEventModel> handler) {
        return handler.handle(tuple.getT1())
                .onErrorResume(message -> {
                    log.error("Error handling the message: " + message.getMessage());
                    retryMessage(tuple, queueName);
                    return Mono.just(1).then();
                })
                .doOnSuccess(message -> deleteMessage(queueName, tuple.getT2()).subscribe());
    }

    private void retryMessage(Tuple2<SNSEventModel, Message> tuple, String queueName) {
        log.info("Executing retry message");
        Map<String, MessageAttributeValue> attributes = tuple.getT2().messageAttributes();
        MessageAttributeValue count = attributes.getOrDefault( "retries", MessageAttributeValue.builder().stringValue("0").build()) ;
        String countString = count.stringValue();
        if(Integer.parseInt(countString) < maxRetries) {
            sqsSender.send(tuple.getT2().body(), getRetryDelay(), countString, queueName).subscribe();
        } else {
            log.info("Discarding message completely !!! " + tuple.getT2().messageId());
        }
    }


    public Mono<Tuple2<SNSEventModel, Message>> mapObject(Message message) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            return Mono.zip(Mono.just(objectMapper.readValue(message.body(), SNSEventModel.class)),
                    Mono.just(message));
        } catch (JsonProcessingException ex) {
            return Mono.error(ex);
        }
    }

    public Mono<DeleteMessageResponse> deleteMessage(String queueName, Message m) {
        DeleteMessageRequest deleteMessageRequest = getDeleteMessageRequest(queueName,
                m.receiptHandle());
        return Mono.fromFuture(client.deleteMessage(deleteMessageRequest))
                .doOnNext(response -> log.info("Message from: " + queueName + " Hash: " + response.hashCode()  + " deleted"))
                .doOnError(message -> log.error("Error to delete a message - posible message duplication: " + message.getMessage()));
    }

    public Flux<Message> getMessages(String queueName) {
        return getReceiveMessageRequest(queueName)
                .flatMap((req) -> Mono.fromFuture(client.receiveMessage(req))
                        .doOnSuccess(response -> log.info("Size: " + response.messages().size()))
                        .doOnError((e) -> {
                            System.out.println(e.getMessage());
                        })
                )
                .flatMapMany((response) -> Flux.fromIterable(response.messages()));

    }

    public Mono<ReceiveMessageRequest> getReceiveMessageRequest(String name) {
        log.info("Getting messages from " + name);
        return Mono.just(
                ReceiveMessageRequest.builder()
                        .queueUrl(name)
                        .maxNumberOfMessages(prefetchCount)
                        .waitTimeSeconds(20)
                        .messageAttributeNames("retries")
                        .build()
        );
    }

    public DeleteMessageRequest getDeleteMessageRequest(String queueName, String receiptHandle) {
        return DeleteMessageRequest.builder().queueUrl(queueName).receiptHandle(receiptHandle).build();
    }

    public Flux<Void> startListener(String queueName, GenericHandler<Void, SNSEventModel> handler) {
        return listen(queueName, handler)
                .doOnSuccess((e) -> log.debug("listen terminated " + queueName))
                .repeat();
    }

    private int getRetryDelay() {
        return retryDelay / 1000;
    }

}
