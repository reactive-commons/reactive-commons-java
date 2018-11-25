package org.reactivecommons.async.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.reactivecommons.async.impl.config.MessageConfig;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;

/**
 * Publicador simple de mensajes en RabbitMQ, se asume y delega la disponibilidad en el communications
 * Evolución: "Ser resiliente en la mayor medida posible a particiones de red con el communications"
 * @deprecated Use DirectAsyncGateway instead
 */
@Deprecated
public abstract class ReplyCommandSender {


    @Autowired
    MessageConfig.BrokerConfig config;

    @Autowired
    DirectAsyncGateway asyncGateway;

    private final ObjectMapper mapper = new ObjectMapper();

    protected <R, C> Mono<R> sendCommand(C command, String commandId, Class<R> type) {
        AsyncQuery<C> asyncQuery = new AsyncQuery<C>(commandId, command);
        return asyncGateway.requestReply(asyncQuery, target(), type);
    }

    protected abstract String target();


}
