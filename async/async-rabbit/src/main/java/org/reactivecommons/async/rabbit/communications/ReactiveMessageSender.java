package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.exceptions.SendFailureNoAckException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.Sender;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.reactivecommons.async.api.DirectAsyncGateway.DELAYED;
import static org.reactivecommons.async.commons.Headers.SOURCE_APPLICATION;

@Slf4j
public class ReactiveMessageSender {
    @Getter
    private final Sender sender;
    private final String sourceApplication;
    private final MessageConverter messageConverter;
    @Getter
    private final TopologyCreator topologyCreator;

    private final int numberOfSenderSubscriptions = 4;
    private final CopyOnWriteArrayList<FluxSink<MyOutboundMessage>> fluxSinkConfirm = new CopyOnWriteArrayList<>();

    private volatile FluxSink<OutboundMessage> fluxSinkNoConfirm;
    private final AtomicLong counter = new AtomicLong();

    private final ExecutorService executorService = Executors.newFixedThreadPool(13, r -> new Thread(r, "RMessageSender1-" + counter.getAndIncrement()));
    private final ExecutorService executorService2 = Executors.newFixedThreadPool(13, r -> new Thread(r, "RMessageSender2-" + counter.getAndIncrement()));


    public ReactiveMessageSender(Sender sender, String sourceApplication, MessageConverter messageConverter, TopologyCreator topologyCreator) {
        this.sender = sender;
        this.sourceApplication = sourceApplication;
        this.messageConverter = messageConverter;
        this.topologyCreator = topologyCreator;
        createSendersWithConfirm(numberOfSenderSubscriptions);
        createSenderNoConfirm();
    }

    private void createSendersWithConfirm(int toAdd) {
        for (int i = 0; i < toAdd; ++i) {
            final AtomicReference<FluxSink<?>> fluxSinkRef = new AtomicReference<>();
            final Flux<MyOutboundMessage> messageSource = Flux.create(fluxSink -> {
                fluxSinkRef.set(fluxSink);
                this.fluxSinkConfirm.add(fluxSink);
            });
            sender.sendWithTypedPublishConfirms(messageSource)
                    .doOnNext((OutboundMessageResult<MyOutboundMessage> outboundMessageResult) -> {
                        final Consumer<Boolean> ackNotifier = outboundMessageResult.getOutboundMessage().getAckNotifier();
                        executorService.submit(() -> ackNotifier.accept(outboundMessageResult.isAck()));
                    })
                    .onErrorResume(ShutdownSignalException.class, throwable -> {
                        log.warn("RabbitMQ connection lost. Trying to recreate sender with confirm...", throwable);
                        fluxSinkConfirm.remove(fluxSinkRef.get());
                        createSendersWithConfirm(1);
                        return Mono.empty();
                    })
                    .subscribe();
        }
    }

    private void createSenderNoConfirm() {
        final Flux<OutboundMessage> messageSourceNoConfirm = Flux.create(fluxSink -> this.fluxSinkNoConfirm = fluxSink);
        sender.send(messageSourceNoConfirm)
                .onErrorResume(ShutdownSignalException.class, throwable -> {
                    log.warn("RabbitMQ connection lost. Trying to recreate sender no confirm...");
                    createSenderNoConfirm();
                    return Mono.empty();
                })
                .subscribe();
    }

    public <T> Mono<Void> sendWithConfirm(T message, String exchange, String routingKey, Map<String, Object> headers, boolean persistent) {
        return Mono.create(monoSink -> {
            Consumer<Boolean> notifier = new AckNotifier(monoSink);
            final MyOutboundMessage outboundMessage = toOutboundMessage(message, exchange, routingKey, headers, notifier, persistent);
            executorService2.submit(() -> {
                FluxSink<MyOutboundMessage> outboundFlux = fluxSinkConfirm.get(random(numberOfSenderSubscriptions));
                outboundFlux.next(outboundMessage);
            });
        });
    }

    private static int random(int max) {
        return (int) (System.currentTimeMillis() % max);
    }


    public <T> Mono<Void> sendNoConfirm(T message, String exchange, String routingKey, Map<String, Object> headers, boolean persistent) {
        fluxSinkNoConfirm.next(toOutboundMessage(message, exchange, routingKey, headers, persistent));
        return Mono.empty();
    }

    public <T> Flux<OutboundMessageResult<?>> sendWithConfirmBatch(Flux<T> messages, String exchange, String routingKey, Map<String, Object> headers, boolean persistent) {
        return messages.map(message -> toOutboundMessage(message, exchange, routingKey, headers, persistent))
                .as(sender::sendWithPublishConfirms)
                .flatMap(result -> result.isAck() ?
                        Mono.empty() :
                        Mono.error(new SendFailureNoAckException("Event no ACK in communications"))
                );
    }

    private static class AckNotifier implements Consumer<Boolean> {
        private final MonoSink<Void> monoSink;

        public AckNotifier(MonoSink<Void> monoSink) {
            this.monoSink = monoSink;
        }

        @Override
        public void accept(Boolean ack) {
            if (ack) {
                monoSink.success();
            } else {
                monoSink.error(new SendFailureNoAckException("No ACK when sending message"));
            }
        }
    }


    @Getter
    static class MyOutboundMessage extends OutboundMessage {

        private final Consumer<Boolean> ackNotifier;

        public MyOutboundMessage(String exchange, String routingKey, AMQP.BasicProperties properties, byte[] body, Consumer<Boolean> ackNotifier) {
            super(exchange, routingKey, properties, body);
            this.ackNotifier = ackNotifier;
        }

    }

    private <T> MyOutboundMessage toOutboundMessage(T object, String exchange, String routingKey, Map<String, Object> headers, Consumer<Boolean> ackNotifier, boolean persistent) {
        final Message message = messageConverter.toMessage(object);
        final AMQP.BasicProperties props = buildMessageProperties(message, headers, persistent);
        return new MyOutboundMessage(exchange, routingKey, props, message.getBody(), ackNotifier);
    }

    private <T> OutboundMessage toOutboundMessage(T object, String exchange, String routingKey, Map<String, Object> headers, boolean persistent) {
        final Message message = messageConverter.toMessage(object);
        final AMQP.BasicProperties props = buildMessageProperties(message, headers, persistent);
        return new OutboundMessage(exchange, routingKey, props, message.getBody());
    }

    private AMQP.BasicProperties buildMessageProperties(Message message, Map<String, Object> headers, boolean persistent) {
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

