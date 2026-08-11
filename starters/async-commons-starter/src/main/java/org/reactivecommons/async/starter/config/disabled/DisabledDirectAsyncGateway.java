package org.reactivecommons.async.starter.config.disabled;

import org.reactivecommons.async.api.DirectAsyncGateway;
import reactor.core.publisher.Mono;

public class DisabledDirectAsyncGateway implements DirectAsyncGateway {

    private static final String SEND_COMMANDS_DISABLED =
            "sendCommands feature is disabled in ReactiveCommonsFeatures. " +
                    "Set sendCommands=true to send commands or queries.";

    @Override
    public <T> Mono<Void> sendCommand(org.reactivecommons.api.domain.Command<T> command, String targetName) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public <T> Mono<Void> sendCommand(org.reactivecommons.api.domain.Command<T> command, String targetName,
                                      long delayMillis) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public <T> Mono<Void> sendCommand(org.reactivecommons.api.domain.Command<T> command, String targetName,
                                      String domain) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public <T> Mono<Void> sendCommand(org.reactivecommons.api.domain.Command<T> command, String targetName,
                                      long delayMillis, String domain) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public Mono<Void> sendCommand(io.cloudevents.CloudEvent command, String targetName) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public Mono<Void> sendCommand(io.cloudevents.CloudEvent command, String targetName, long delayMillis) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public Mono<Void> sendCommand(io.cloudevents.CloudEvent command, String targetName, String domain) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public Mono<Void> sendCommand(io.cloudevents.CloudEvent command, String targetName, long delayMillis,
                                  String domain) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public <T, R> Mono<R> requestReply(org.reactivecommons.async.api.AsyncQuery<T> query, String targetName,
                                       Class<R> type) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public <T, R> Mono<R> requestReply(org.reactivecommons.async.api.AsyncQuery<T> query, String targetName,
                                       Class<R> type, String domain) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public <R extends io.cloudevents.CloudEvent> Mono<R> requestReply(io.cloudevents.CloudEvent query,
                                                                      String targetName, Class<R> type) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public <R extends io.cloudevents.CloudEvent> Mono<R> requestReply(io.cloudevents.CloudEvent query,
                                                                      String targetName, Class<R> type,
                                                                      String domain) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }

    @Override
    public <T> Mono<Void> reply(T response, org.reactivecommons.async.api.From from) {
        return Mono.error(new IllegalStateException(SEND_COMMANDS_DISABLED));
    }
}