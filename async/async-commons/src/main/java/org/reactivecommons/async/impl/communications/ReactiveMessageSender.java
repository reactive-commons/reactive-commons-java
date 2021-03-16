package org.reactivecommons.async.impl.communications;

import com.rabbitmq.client.AMQP;
import org.reactivecommons.async.parent.converters.MessageConverter;
import org.reactivecommons.async.parent.exceptions.SendFailureNoAckException;
import org.reactivecommons.async.parent.communications.Message;
import reactor.core.publisher.*;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.Sender;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.reactivecommons.async.impl.Headers.SOURCE_APPLICATION;

public class ReactiveMessageSender {

    private final Sender sender;
    private final String sourceApplication;
    private final MessageConverter messageConverter;
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

        for (int i = 0; i < numberOfSenderSubscriptions; ++i) {
            final Flux<MyOutboundMessage> messageSource = Flux.create(fluxSinkConfirm::add);
            sender.sendWithTypedPublishConfirms(messageSource).doOnNext((OutboundMessageResult<MyOutboundMessage> outboundMessageResult) -> {
                final Consumer<Boolean> ackNotifier = outboundMessageResult.getOutboundMessage().getAckNotifier();
                executorService.submit(() -> ackNotifier.accept(outboundMessageResult.isAck()));
            }).subscribe();
        }

        final Flux<OutboundMessage> messageSourceNoConfirm = Flux.create(fluxSink -> {
            this.fluxSinkNoConfirm = fluxSink;
        });
        sender.send(messageSourceNoConfirm).subscribe();

    }

    public <T> Mono<Void> sendWithConfirm(T message, String exchange, String routingKey, Map<String, Object> headers, boolean persistent) {
        return Mono.create(monoSink -> {
            Consumer<Boolean> notifier = new AckNotifier(monoSink);
            final MyOutboundMessage outboundMessage = toOutboundMessage(message, exchange, routingKey, headers, notifier, persistent);
            executorService2.submit(() -> fluxSinkConfirm.get((int) (System.currentTimeMillis()%numberOfSenderSubscriptions)).next(outboundMessage));
        });
    }


    public <T> Mono<Void> sendNoConfirm(T message, String exchange, String routingKey, Map<String, Object> headers, boolean persistent) {
        fluxSinkNoConfirm.next(toOutboundMessage(message, exchange, routingKey, headers, persistent));
        return Mono.empty();
    }

    public <T> Flux<OutboundMessageResult> sendWithConfirmBatch(Flux<T> messages, String exchange, String routingKey, Map<String, Object> headers, boolean persistent) {
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



    static class MyOutboundMessage extends OutboundMessage{

        private final Consumer<Boolean> ackNotifier;

        public MyOutboundMessage(String exchange, String routingKey, AMQP.BasicProperties properties, byte[] body, Consumer<Boolean> ackNotifier) {
            super(exchange, routingKey, properties, body);
            this.ackNotifier = ackNotifier;
        }

        public Consumer<Boolean> getAckNotifier() {
            return ackNotifier;
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
        return new AMQP.BasicProperties.Builder()
                .contentType(properties.getContentType())
                .appId(sourceApplication)
                .contentEncoding(properties.getContentEncoding())
                .deliveryMode(persistent ? 2 : 1)
                .timestamp(new Date())
                .messageId(UUID.randomUUID().toString())
                .headers(baseHeaders).build();
    }

    public reactor.rabbitmq.Sender getSender() {
        return sender;
    }

    public TopologyCreator getTopologyCreator() {
        return topologyCreator;
    }
}

