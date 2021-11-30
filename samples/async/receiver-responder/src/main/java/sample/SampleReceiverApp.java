package sample;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableMessageListeners;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

import static org.reactivecommons.async.api.HandlerRegistry.register;
import static reactor.core.publisher.Mono.just;

@SpringBootApplication
@EnableMessageListeners
@EnableDomainEventBus
@EnableDirectAsyncGateway
@Log
public class SampleReceiverApp {
    public static void main(String[] args) {
        SpringApplication.run(SampleReceiverApp.class, args);
    }

    //@Bean
    public HandlerRegistry handlerRegistry(MemberReceiver receiver) {
        return register()
                .serveQuery("serveQuery.register.member", receiver)
                .serveQuery("serveQuery.register.member.new", new QueryHandler<MemberRegisteredEvent,
                        AddMemberCommand>() {
                    @Override
                    public Mono<MemberRegisteredEvent> handle(AddMemberCommand command) {
                        return just(new MemberRegisteredEvent("42", 69));
                    }
                })
                .serveQuery("test.query", message -> {
                    return Mono.error(new RuntimeException("Falla Generada Query"));
                }, AddMemberCommand.class);
    }

    @Bean
    public HandlerRegistry handlerRegistrySubs(DirectAsyncGateway gateway) {
        return HandlerRegistry.register()
                .handleDynamicEvents("dynamic.*", message -> Mono.empty(), Object.class)
                .listenEvent("fixed.event", message -> Mono.empty(), Object.class)
                .serveQuery("query1", message -> {
                    log.info("resolving from direct query");
                    return just(new RespQuery1("Ok", message));
                }, Call.class)
                .serveQuery("sample.query.*", message -> {
                    log.info("resolving from direct query");
                    return just(new RespQuery1("Ok", message));
                }, Call.class)
                .serveQuery("query2", (from, message) -> {
                    log.info("resolving from delegate query");
                    return gateway.reply(new RespQuery1("Ok", message), from).then();
                }, Call.class);
    }

    //@Bean
    public HandlerRegistry handlerRegistryForEmpty(EmptyReceiver emptyReceiver) {
        return register()
                .serveQuery("serveQuery.empty", emptyReceiver);
    }

    //@Bean
    public HandlerRegistry eventListeners(SampleUseCase useCase) {
        return register()
                .listenEvent("persona.registrada", useCase::reactToPersonaEvent, MemberRegisteredEvent.class);
    }

    @Bean
    public SampleUseCase sampleUseCase(DomainEventBus eventBus) {
        return new SampleUseCase(eventBus);
    }

    @Data
    @AllArgsConstructor
    static class RespQuery1 {
        private String response;
        private Call request;
    }

    @Data
    @AllArgsConstructor
    static class Call {
        private String name;
        private String phone;
    }

    @Data
    @AllArgsConstructor
    static class CallResponse {
        private String message;
        private Integer code;
    }

    @RequiredArgsConstructor
    public static class SampleUseCase {
        private final DomainEventBus eventBus;

        Mono<Void> reactToPersonaEvent(DomainEvent<MemberRegisteredEvent> event) {
            return Mono.from(eventBus.emit(new DomainEvent<>("persona.procesada", "213", event.getData())))
                    .doOnSuccess(_v -> System.out.println("Persona procesada"));
        }
    }

}
