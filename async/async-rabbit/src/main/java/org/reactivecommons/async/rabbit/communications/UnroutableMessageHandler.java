package org.reactivecommons.async.rabbit.communications;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessageResult;

@FunctionalInterface
public interface UnroutableMessageHandler {
    /**
     * Processes a message that was returned by RabbitMQ because it could not be routed.
     *
     * @param result The result of the outbound message, containing the original message and return details.
     */
    Mono<Void> processMessage(OutboundMessageResult<MyOutboundMessage> result);
}