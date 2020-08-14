package org.reactivecommons.async.impl.communications;

import com.rabbitmq.client.AMQP;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.exceptions.SendFailureNoAckException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.publisher.UnicastProcessor;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import reactor.rabbitmq.Sender;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.reactivecommons.async.impl.Headers.SOURCE_APPLICATION;

public class ReactiveMessageSender {

    private final Sender sender;
    private final String sourceApplication;
    private final MessageConverter messageConverter;
    private final TopologyCreator topologyCreator;
    private final UnicastProcessor<MyOutboundMessage> processor = UnicastProcessor.create();
    private final UnicastProcessor<MyOutboundMessage> processor2 = UnicastProcessor.create();
    private final UnicastProcessor<MyOutboundMessage> processor3 = UnicastProcessor.create();
    private final UnicastProcessor<MyOutboundMessage> processor4 = UnicastProcessor.create();
    private final UnicastProcessor<MyOutboundMessage> processors[] = new UnicastProcessor[]{processor, processor2, processor3, processor4};
    private final ExecutorService executorService = new ThreadPoolExecutor(12, 256,
        60L, TimeUnit.SECONDS,
        new SynchronousQueue<Runnable>());


    public ReactiveMessageSender(Sender sender, String sourceApplication, MessageConverter messageConverter, TopologyCreator topologyCreator) {
        this.sender = sender;
        this.sourceApplication = sourceApplication;
        this.messageConverter = messageConverter;
        this.topologyCreator = topologyCreator;
        sender.sendWithTypedPublishConfirms(processor).doOnNext((OutboundMessageResult<MyOutboundMessage> outboundMessageResult) -> {
            final Consumer<Boolean> ackNotifier = outboundMessageResult.getOutboundMessage().getAckNotifier();
            executorService.submit(() -> ackNotifier.accept(outboundMessageResult.isAck()));
        }).subscribe();

        sender.sendWithTypedPublishConfirms(processor2).doOnNext(outboundMessageResult -> {
            final Consumer<Boolean> ackNotifier = outboundMessageResult.getOutboundMessage().getAckNotifier();
            executorService.submit(() -> ackNotifier.accept(outboundMessageResult.isAck()));
        }).subscribe();

        sender.sendWithTypedPublishConfirms(processor3).doOnNext(outboundMessageResult -> {
            final Consumer<Boolean> ackNotifier = outboundMessageResult.getOutboundMessage().getAckNotifier();
            executorService.submit(() -> ackNotifier.accept(outboundMessageResult.isAck()));
        }).subscribe();

        sender.sendWithTypedPublishConfirms(processor4).doOnNext(outboundMessageResult -> {
            final Consumer<Boolean> ackNotifier = outboundMessageResult.getOutboundMessage().getAckNotifier();
            executorService.submit(() -> ackNotifier.accept(outboundMessageResult.isAck()));
        }).subscribe();
    }


    public <T> Mono<Void> sendWithConfirm(T message, String exchange, String routingKey, Map<String, Object> headers) {
        return Mono.create(monoSink -> {
            Consumer<Boolean> notifier = new AckNotifier(monoSink);
            final MyOutboundMessage outboundMessage = toOutboundMessage(message, exchange, routingKey, headers, notifier);
            final int idx = (int)(System.currentTimeMillis() % processors.length);
            processors[idx].onNext(outboundMessage);
        });
    }

    public <T> Flux<OutboundMessageResult> sendWithConfirm2(Flux<T> messages, String exchange, String routingKey, Map<String, Object> headers) {
        return messages.map(message -> toOutboundMessage(message, exchange, routingKey, headers))
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

    public <T> Mono<Void> sendNoConfirm(Flux<T> messages, String exchange, String routingKey, Map<String, Object> headers) {
//        return messages.map(message -> toOutboundMessage(message, exchange, routingKey, headers))
//            .as(sender::send);
        return null;
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

    private <T> MyOutboundMessage toOutboundMessage(T object, String exchange, String routingKey, Map<String, Object> headers, Consumer<Boolean> ackNotifier) {
        final Message message = messageConverter.toMessage(object);
        final AMQP.BasicProperties props = buildMessageProperties(message, headers);
        return new MyOutboundMessage(exchange, routingKey, props, message.getBody(), ackNotifier);
    }

    private <T> OutboundMessage toOutboundMessage(T object, String exchange, String routingKey, Map<String, Object> headers) {
        final Message message = messageConverter.toMessage(object);
        final AMQP.BasicProperties props = buildMessageProperties(message, headers);
        return new OutboundMessage(exchange, routingKey, props, message.getBody());
    }

    private AMQP.BasicProperties buildMessageProperties(Message message, Map<String, Object> headers) {
        final Message.Properties properties = message.getProperties();
        final Map<String, Object> baseHeaders = new HashMap<>(properties.getHeaders());
        baseHeaders.putAll(headers);
        baseHeaders.put(SOURCE_APPLICATION, sourceApplication);
        return new AMQP.BasicProperties.Builder()
                .contentType(properties.getContentType())
                .appId(sourceApplication)
                .contentEncoding(properties.getContentEncoding())
                .deliveryMode(2)
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

