package org.reactivecommons.async.api;

import io.cloudevents.CloudEvent;
import org.reactivecommons.api.domain.Command;
import reactor.core.publisher.Mono;

public interface DirectAsyncGateway {
    public static final String DELAYED = "rc-delay";

    <T> Mono<Void> sendCommand(Command<T> command, String targetName);

    <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis);

    <T> Mono<Void> sendCommand(Command<T> command, String targetName, String domain);

    <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis, String domain);

    Mono<Void> sendCloudCommand(CloudEvent command, String targetName);

    Mono<Void> sendCloudCommand(CloudEvent command, String targetName, String domain);

    Mono<Void> sendCloudCommand(CloudEvent command, String targetName, long delayMillis, String domain);

    <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type);

    <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type, String domain);

    <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type);

    <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type, String domain);

    <T> Mono<Void> reply(T response, From from);
}
