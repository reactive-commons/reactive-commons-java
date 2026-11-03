package sample;

import io.cloudevents.CloudEvent;
import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableEventListeners;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;
import sample.model.Constants;

@Slf4j
@Configuration
@EnableEventListeners
public class ListenerConfig {

    @Bean
    @Primary
    public HandlerRegistry handlerRegistrySubs() {
        return HandlerRegistry.register()
                .listenEvent(Constants.DATA_RESET, this::reset, String.class)
                .listenCloudEvent("event-name", this::reset2);
    }

    private Mono<Void> reset2(CloudEvent cloudEvent) {
        log.info("reset2: " + cloudEvent);
        return Mono.empty();
    }

    public Mono<Void> reset(DomainEvent<String> ignored) {
        log.info("reset: {}", ignored);
        return Mono.empty();
    }
}
