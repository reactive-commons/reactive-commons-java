package us.sofka.commons.reactive.async.api;

import reactor.core.publisher.Mono;

import java.util.function.Function;

public interface AsyncQueryHandler<T, R> extends Function<T, Mono<R>> {


}
