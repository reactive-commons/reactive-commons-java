package org.reactivecommons.async.rabbit.config;

import com.rabbitmq.client.ConnectionFactory;

@FunctionalInterface
public interface ConnectionFactoryProvider {
    ConnectionFactory getConnectionFactory();
}
