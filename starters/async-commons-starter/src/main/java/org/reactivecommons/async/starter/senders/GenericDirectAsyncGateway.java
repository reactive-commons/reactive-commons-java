package org.reactivecommons.async.starter.senders;

import io.cloudevents.CloudEvent;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.From;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentMap;

public class GenericDirectAsyncGateway implements DirectAsyncGateway {
    private final ConcurrentMap<String, DirectAsyncGateway> directAsyncGateways;
    private final String defaultDomain;

    public GenericDirectAsyncGateway(ConcurrentMap<String, DirectAsyncGateway> directAsyncGateways, String defaultDomain) {
        this.directAsyncGateways = directAsyncGateways;
        this.defaultDomain = defaultDomain;
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName) {
        return sendCommand(command, targetName, defaultDomain);
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis) {
        return sendCommand(command, targetName, delayMillis, defaultDomain);
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
        return sendCommand(command, targetName, defaultDomain);
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName, long delayMillis) {
        return sendCommand(command, targetName, delayMillis, defaultDomain);
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
        return requestReply(query, targetName, type, defaultDomain);
    }

    @Override
    public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type, String domain) {
        return directAsyncGateways.get(domain).requestReply(query, targetName, type);
    }

    @Override
    public <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type) {
        return requestReply(query, targetName, type, defaultDomain);
    }

    @Override
    public <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type, String domain) {
        return directAsyncGateways.get(domain).requestReply(query, targetName, type);
    }

    @Override
    public <T> Mono<Void> reply(T response, From from) {
        return directAsyncGateways.get(defaultDomain).reply(response, from);
    }
}
