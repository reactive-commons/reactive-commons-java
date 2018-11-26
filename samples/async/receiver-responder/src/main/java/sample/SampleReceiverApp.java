package sample;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.HandlerRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

@SpringBootApplication
public class SampleReceiverApp {
    public static void main(String[] args) {
        SpringApplication.run(SampleReceiverApp.class, args);
    }

    @Bean
    public HandlerRegistry handlerRegistry(MemberReceiver receiver) {
        return HandlerRegistry.register()
            .serveQuery("serveQuery.register.member", receiver)
            .serveQuery("serveQuery.register.member.new", new QueryHandler<MemberRegisteredEvent, AddMemberCommand>(){
                @Override
                public Mono<MemberRegisteredEvent> handle(AddMemberCommand command) {
                    return Mono.just(new MemberRegisteredEvent("42", 69));
                }
            });
    }

    @Bean
    public HandlerRegistry eventListeners(SampleUseCase useCase) {
        return HandlerRegistry.register()
            .listenEvent("persona.registrada", useCase::reactToPersonaEvent, MemberRegisteredEvent.class);
    }


    @Bean
    public SampleUseCase sampleUseCase(DomainEventBus eventBus) {
        return new SampleUseCase(eventBus);
    }

    @RequiredArgsConstructor
    public static class SampleUseCase {
        private final DomainEventBus eventBus;

        public Mono<Void> reactToPersonaEvent(DomainEvent<MemberRegisteredEvent> event){
            return Mono.from(eventBus.emit(new DomainEvent<>("persona.procesada", "213", event.getData())))
                .doOnSuccess(_v -> System.out.println("Persona procesada"));
        }
    }

}
