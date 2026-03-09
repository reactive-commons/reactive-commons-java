package org.reactivecommons.async.rabbit.communications;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;

/**
 * Convenience factory for creating topology specification objects.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ResourcesSpecification {

    public static ExchangeSpecification exchange(String name) {
        return ExchangeSpecification.exchange(name);
    }

    public static QueueSpecification queue(String name) {
        return QueueSpecification.queue(name);
    }

    public static BindingSpecification binding(String exchange, String routingKey, String queue) {
        return BindingSpecification.queueBinding(exchange, routingKey, queue);
    }
}
