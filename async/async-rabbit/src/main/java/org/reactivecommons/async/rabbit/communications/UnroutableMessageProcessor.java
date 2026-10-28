package org.reactivecommons.async.rabbit.communications;

import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.rabbit.RabbitMessage;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;

@Log
@NoArgsConstructor
public class UnroutableMessageProcessor implements UnroutableMessageHandler {

    /**
     * Logs the unroutable message: only the beginning of its body is written.
     */
    @Override
    public Mono<Void> processMessage(OutboundMessageResult<OutboundMessage> result) {
        var outboundMessage = result.outboundMessage();
        var properties = outboundMessage.getProperties();
        var body = outboundMessage.getBody();
        log.severe(() -> "Unroutable message: exchange=" + outboundMessage.getExchange()
                + ", routingKey=" + outboundMessage.getRoutingKey()
                + ", messageId=" + (properties != null ? properties.getMessageId() : null)
                + ", size=" + body.length + " bytes"
                + ", properties=" + properties
                + ", body=" + RabbitMessage.bodyPreview(body));
        return Mono.empty();
    }
}
