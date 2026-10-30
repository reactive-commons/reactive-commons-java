package org.reactivecommons.async.rabbit.communications;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.rabbit.RabbitMessage;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;

@Slf4j
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
        log.error("Unroutable message: exchange={}, routingKey={}, messageId={}, size={} bytes, properties={}, body={}",
                outboundMessage.getExchange(), outboundMessage.getRoutingKey(),
                properties != null ? properties.getMessageId() : null, body.length, properties,
                RabbitMessage.bodyPreview(body));
        return Mono.empty();
    }
}
