package us.sofka.commons.reactive.async;

import com.rabbitmq.client.AMQP;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.Sender;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static reactor.core.publisher.Mono.just;

public class ReactiveMessageSender {

    private final Sender sender;
    private final String exchange;
    private final String sourceApplication;
    private final Jackson2JsonMessageConverter messageConverter = new Jackson2JsonMessageConverter();

    public ReactiveMessageSender(Sender sender, String exchange, String sourceApplication) {
        this.sender = sender;
        this.exchange = exchange;
        this.sourceApplication = sourceApplication;
    }

    public <T> Mono<Void> sendWithConfirm(T message, String routingKey, Map<String, Object> headers) {
        return sender.sendWithPublishConfirms(just(toOutboundMessage(message, routingKey, headers))).flatMap(result -> result.isAck() ?
            Mono.empty() :
            Mono.error(new SendFailureNoAckException("Event no ACK in broker"))
        ).then();
    }

    private <T> OutboundMessage toOutboundMessage(T object, String routingKey, Map<String, Object> headers) {
        final Message message = messageConverter.toMessage(object, null);
        final AMQP.BasicProperties props = buildMessageProperties(message, headers);
        return new OutboundMessage(exchange, routingKey, props, message.getBody());
    }

    private AMQP.BasicProperties buildMessageProperties(Message message, Map<String, Object> headers) {
        final MessageProperties properties = message.getMessageProperties();
        final Map<String, Object> baseHeaders = new HashMap<>(properties.getHeaders());
        baseHeaders.putAll(headers);
        baseHeaders.put("sourceApplication", sourceApplication);
        return new AMQP.BasicProperties.Builder()
            .contentType(properties.getContentType())
            .appId(sourceApplication)
            .contentEncoding(properties.getContentEncoding())
            .deliveryMode(1)
            .timestamp(new Date())
            .messageId(UUID.randomUUID().toString())
            .headers(headers).build();
    }
}

class SendFailureNoAckException extends RuntimeException {
    public SendFailureNoAckException() {
    }

    public SendFailureNoAckException(String message) {
        super(message);
    }
}
