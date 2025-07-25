package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.AMQP;
import lombok.Getter;
import lombok.extern.java.Log;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.SendOptions;
import reactor.rabbitmq.Sender;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.reactivecommons.async.api.DirectAsyncGateway.DELAYED;
import static org.reactivecommons.async.commons.Headers.SOURCE_APPLICATION;

@Log
public class ReactiveMessageSender {
    @Getter
    private final Sender sender;
    private final String sourceApplication;
    private final MessageConverter messageConverter;
    @Getter
    private final TopologyCreator topologyCreator;
    private final boolean isMandatory;
    private final UnroutableMessageHandler unroutableMessageHandler;

    private static final int NUMBER_OF_SENDER_SUBSCRIPTIONS = 4;
    private final CopyOnWriteArrayList<FluxSink<MyOutboundMessage>> fluxSinkConfirm = new CopyOnWriteArrayList<>();

    private volatile FluxSink<OutboundMessage> fluxSinkNoConfirm;
    private final AtomicLong counter = new AtomicLong();

    private final ExecutorService executorService = Executors.newFixedThreadPool(
            13, r -> new Thread(r, "RMessageSender1-" + counter.getAndIncrement())
    );
    private final ExecutorService executorService2 = Executors.newFixedThreadPool(
            13, r -> new Thread(r, "RMessageSender2-" + counter.getAndIncrement())
    );

    public ReactiveMessageSender(Sender sender, String sourceApplication,
                                 MessageConverter messageConverter, TopologyCreator topologyCreator,
                                 boolean isMandatory, UnroutableMessageHandler unroutableMessageHandler) {
        this.sender = sender;
        this.sourceApplication = sourceApplication;
        this.messageConverter = messageConverter;
        this.topologyCreator = topologyCreator;
        this.isMandatory = isMandatory && unroutableMessageHandler != null;
        this.unroutableMessageHandler = unroutableMessageHandler;

        System.out.println("ReactiveMessageSender initialized with mandatory: " + isMandatory);
        System.out.println("onReturnedCallback: " + unroutableMessageHandler);

        initializeSenders();
    }

    private void initializeSenders() {
        for (int i = 0; i < NUMBER_OF_SENDER_SUBSCRIPTIONS; ++i) {
            final Flux<MyOutboundMessage> messageSource = Flux.create(fluxSinkConfirm::add);
            sender.sendWithTypedPublishConfirms(messageSource, new SendOptions().trackReturned(isMandatory))
                    .doOnNext((OutboundMessageResult<MyOutboundMessage> outboundMessageResult) -> {
                        System.out.println("MANDATORY: " + isMandatory);
                        if (outboundMessageResult.isReturned()) {
                            System.out.println("CALLBACK: " + unroutableMessageHandler);
                            this.unroutableMessageHandler.processMessage(outboundMessageResult);
                        }
                        final Consumer<Boolean> ackNotifier = outboundMessageResult.getOutboundMessage().getAckNotifier();
                        executorService.submit(() -> ackNotifier.accept(outboundMessageResult.isAck()));
                    }).subscribe();
        }

        final Flux<OutboundMessage> messageSourceNoConfirm = Flux.create(fluxSink ->
                this.fluxSinkNoConfirm = fluxSink
        );
        sender.send(messageSourceNoConfirm).subscribe();
    }

    public <T> Mono<Void> sendWithConfirm(T message, String exchange, String routingKey,
                                          Map<String, Object> headers, boolean persistent) {
        return Mono.create(monoSink -> {
            Consumer<Boolean> notifier = new AckNotifier(monoSink);
            final MyOutboundMessage outboundMessage = toOutboundMessage(
                    message, exchange, routingKey, headers, notifier, persistent
            );
            executorService2.submit(() -> fluxSinkConfirm.get(
                    (int) (System.currentTimeMillis() % NUMBER_OF_SENDER_SUBSCRIPTIONS)).next(outboundMessage)
            );
        });
    }


    public <T> Mono<Void> sendNoConfirm(T message, String exchange, String routingKey,
                                        Map<String, Object> headers, boolean persistent) {
        fluxSinkNoConfirm.next(toOutboundMessage(message, exchange, routingKey, headers, persistent));
        return Mono.empty();
    }

    public <T> Flux<OutboundMessageResult> sendWithConfirmBatch(Flux<T> messages, String exchange, String routingKey,
                                                                Map<String, Object> headers, boolean persistent) {
        return messages.map(message -> toOutboundMessage(message, exchange, routingKey, headers, persistent))
                .as(sender::sendWithPublishConfirms)
                .flatMap(result -> result.isAck() ?
                        Mono.empty() :
                        Mono.error(new SendFailureNoAckException("Event no ACK in communications"))
                );
    }

    public Mono<Void> sendMessage(Object message, String exchange, String routingKey, Map<String, Object> headers) {
        return sendNoConfirm(message, exchange, routingKey, headers, true)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.severe("Failed to send unroutable message: " + e.getMessage()));
    }

    private record AckNotifier(MonoSink<Void> monoSink) implements Consumer<Boolean> {

        @Override
        public void accept(Boolean ack) {
            if (Boolean.TRUE.equals(ack)) {
                monoSink.success();
            } else {
                monoSink.error(new SendFailureNoAckException("No ACK when sending message"));
            }
        }
    }

    private <T> MyOutboundMessage toOutboundMessage(T object, String exchange,
                                                    String routingKey, Map<String, Object> headers,
                                                    Consumer<Boolean> ackNotifier, boolean persistent) {
        final Message message = messageConverter.toMessage(object);
        final AMQP.BasicProperties props = buildMessageProperties(message, headers, persistent);
        return new MyOutboundMessage(exchange, routingKey, props, message.getBody(), ackNotifier);
    }

    private <T> OutboundMessage toOutboundMessage(T object, String exchange, String routingKey,
                                                  Map<String, Object> headers, boolean persistent) {
        final Message message = messageConverter.toMessage(object);
        final AMQP.BasicProperties props = buildMessageProperties(message, headers, persistent);
        return new OutboundMessage(exchange, routingKey, props, message.getBody());
    }

    private AMQP.BasicProperties buildMessageProperties(Message message, Map<String, Object> headers,
                                                        boolean persistent) {
        final Message.Properties properties = message.getProperties();
        final Map<String, Object> baseHeaders = new HashMap<>(properties.getHeaders());
        baseHeaders.putAll(headers);
        baseHeaders.put(SOURCE_APPLICATION, sourceApplication);
        AMQP.BasicProperties.Builder builder = new AMQP.BasicProperties.Builder()
                .contentType(properties.getContentType())
                .appId(sourceApplication)
                .contentEncoding(properties.getContentEncoding())
                .deliveryMode(persistent ? 2 : 1)
                .timestamp(new Date())
                .messageId(UUID.randomUUID().toString())
                .headers(baseHeaders);
        if (headers.containsKey(DELAYED)) {
            builder.expiration((String) headers.get(DELAYED));
        }
        return builder.build();
    }
}

