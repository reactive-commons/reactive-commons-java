package org.reactivecommons.async.impl.config;

import com.rabbitmq.client.ConnectionFactory;

@FunctionalInterface
public interface ConnectionFactoryProvider {
    ConnectionFactory getConnectionFactory();
}
