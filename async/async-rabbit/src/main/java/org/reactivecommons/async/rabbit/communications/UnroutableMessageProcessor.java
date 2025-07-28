package org.reactivecommons.async.rabbit.communications;

import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessageResult;

import java.nio.charset.StandardCharsets;

@Log
@NoArgsConstructor
public class UnroutableMessageProcessor implements UnroutableMessageHandler {


    @Override
    public Mono<Void> processMessage(OutboundMessageResult<MyOutboundMessage> result) {
        var outboundMessage = result.getOutboundMessage();
        log.severe("Unroutable message: exchange=" + outboundMessage.getExchange()
                + ", routingKey=" + outboundMessage.getRoutingKey()
                + ", body=" + new String(outboundMessage.getBody(), StandardCharsets.UTF_8)
                + ", properties=" + outboundMessage.getProperties()
        );
        return Mono.empty();
    }
}
