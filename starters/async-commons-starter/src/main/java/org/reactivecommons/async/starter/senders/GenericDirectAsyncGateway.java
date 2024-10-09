package org.reactivecommons.async.starter.senders;

import io.cloudevents.CloudEvent;
import lombok.AllArgsConstructor;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.From;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentMap;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@AllArgsConstructor
public class GenericDirectAsyncGateway implements DirectAsyncGateway {
    private final ConcurrentMap<String, DirectAsyncGateway> directAsyncGateways;

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName) {
        return sendCommand(command, targetName, DEFAULT_DOMAIN);
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis) {
        return sendCommand(command, targetName, delayMillis, DEFAULT_DOMAIN);
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, String domain) {
        return directAsyncGateways.get(domain).sendCommand(command, targetName);
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis, String domain) {
        return directAsyncGateways.get(domain).sendCommand(command, targetName, delayMillis);
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName) {
        return sendCommand(command, targetName, DEFAULT_DOMAIN);
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName, long delayMillis) {
        return sendCommand(command, targetName, delayMillis, DEFAULT_DOMAIN);
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName, String domain) {
        return directAsyncGateways.get(domain).sendCommand(command, targetName);
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName, long delayMillis, String domain) {
        return directAsyncGateways.get(domain).sendCommand(command, targetName, delayMillis);
    }

    @Override
    public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type) {
        return requestReply(query, targetName, type, DEFAULT_DOMAIN);
    }

    @Override
    public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type, String domain) {
        return directAsyncGateways.get(domain).requestReply(query, targetName, type);
    }

    @Override
    public <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type) {
        return requestReply(query, targetName, type, DEFAULT_DOMAIN);
    }

    @Override
    public <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type, String domain) {
        return directAsyncGateways.get(domain).requestReply(query, targetName, type);
    }

    @Override
    public <T> Mono<Void> reply(T response, From from) {
        return directAsyncGateways.get(DEFAULT_DOMAIN).reply(response, from);
    }
}
