package org.reactivecommons.async.kafka;

import io.cloudevents.CloudEvent;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.From;
import reactor.core.publisher.Mono;

public class KafkaDirectAsyncGateway implements DirectAsyncGateway {

    public static final String NOT_IMPLEMENTED_YET = "Not implemented yet";

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName) {
        return notImplemented();
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis) {
        return notImplemented();
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, String domain) {
        return notImplemented();
    }

    @Override
    public <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis, String domain) {
        return notImplemented();
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName) {
        return notImplemented();
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName, long delayMillis) {
        return notImplemented();
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName, String domain) {
        return notImplemented();
    }

    @Override
    public Mono<Void> sendCommand(CloudEvent command, String targetName, long delayMillis, String domain) {
        return notImplemented();
    }

    @Override
    public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type) {
        return notImplemented();
    }

    @Override
    public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type, String domain) {
        return notImplemented();
    }

    @Override
    public <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type) {
        return notImplemented();
    }

    @Override
    public <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type,
                                                       String domain) {
        return notImplemented();
    }

    @Override
    public <T> Mono<Void> reply(T response, From from) {
        return notImplemented();
    }

    /**
     * The error is given through {@code onError}, not thrown, so the error handlers of the caller's chain see it.
     */
    private static <T> Mono<T> notImplemented() {
        return Mono.error(() -> new UnsupportedOperationException(NOT_IMPLEMENTED_YET));
    }
}
