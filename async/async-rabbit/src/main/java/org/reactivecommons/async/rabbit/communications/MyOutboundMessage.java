package org.reactivecommons.async.rabbit.communications;

import com.rabbitmq.client.AMQP;
import lombok.Getter;
import reactor.rabbitmq.OutboundMessage;

import java.util.function.Consumer;

@Getter
public class MyOutboundMessage extends OutboundMessage {

    private final Consumer<Boolean> ackNotifier;

    public MyOutboundMessage(
            String exchange, String routingKey, AMQP.BasicProperties properties,
            byte[] body, Consumer<Boolean> ackNotifier
    ) {
        super(exchange, routingKey, properties, body);
        this.ackNotifier = ackNotifier;
    }
}
