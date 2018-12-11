package sample;

import org.reactivecommons.async.api.handlers.QueryHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class EmptyReceiver implements QueryHandler<String, String> {
    @Override
    public Mono<String> handle(String message) {
        return Mono.empty();
    }
}
