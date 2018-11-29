package org.reactivecommons.async.api;

import org.reactivecommons.api.domain.Command;
import reactor.core.publisher.Mono;

public interface DirectAsyncGateway {
    <T> Mono<Void> sendCommand(Command<T> command, String targetName);
    <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type);
}
