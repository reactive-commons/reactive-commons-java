package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.ConnectionFactory;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;

/**
 * Interface for customizing the RabbitMQ ConnectionFactory.
 */
@FunctionalInterface
public interface ConnectionFactoryCustomizer {

    ConnectionFactory customize(ConnectionFactory connectionFactory, AsyncProps asyncProps);

}
