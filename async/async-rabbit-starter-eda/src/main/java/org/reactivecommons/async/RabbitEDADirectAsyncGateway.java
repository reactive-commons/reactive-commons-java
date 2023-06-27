package org.reactivecommons.async;

import io.micrometer.core.instrument.MeterRegistry;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.reply.ReactiveReplyRouter;
import org.reactivecommons.async.rabbit.RabbitDirectAsyncGateway;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.ConnectionManager;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

public class RabbitEDADirectAsyncGateway extends RabbitDirectAsyncGateway {
    private final ConnectionManager manager;

    public RabbitEDADirectAsyncGateway(BrokerConfig config, ReactiveReplyRouter router, ConnectionManager manager, String exchange, MessageConverter converter, MeterRegistry meterRegistry) {
        super(config, router, manager.getSender(DEFAULT_DOMAIN), exchange, converter, meterRegistry);
        this.manager = manager;
    }

    @Override
    protected ReactiveMessageSender resolveSender(String domain) {
        return manager.getSender(domain);
    }
}
