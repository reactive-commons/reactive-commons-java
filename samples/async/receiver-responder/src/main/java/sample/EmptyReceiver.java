package sample;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class EmptyReceiver implements QueryHandler<String, String> {
    @Override
    public Mono<String> handle(String message) {
        return Mono.delay(Duration.ofSeconds(2)).log("EmptyReceiver").then(Mono.empty());
    }
}