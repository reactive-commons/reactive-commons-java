package org.reactivecommons.async.kafka;

import io.cloudevents.CloudEvent;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.From;
import reactor.core.publisher.Mono;

public class KafkaDirectAsyncGateway implements DirectAsyncGateway {
    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, String domain) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis, String domain) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName, long delayMillis) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName, String domain) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName, long delayMillis, String domain) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type, String domain) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type, String domain) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public <T> Mono<Void> reply(T response, From from) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}