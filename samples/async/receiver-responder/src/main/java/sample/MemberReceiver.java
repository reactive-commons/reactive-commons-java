package sample;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Random;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MemberReceiver implements QueryHandler<MemberRegisteredEvent, AddMemberCommand> {

    private final DomainEventBus eventBus;

    @Override
    public Mono<MemberRegisteredEvent> handle(AddMemberCommand command) {
        if(new Random().nextInt()%2 == 0){
            System.out.println("Causando error!!");
            throw new RuntimeException("Error Causado");
        }
        return eventBus.emit(new DomainEvent<>("persona.registrada", "342", new MemberRegisteredEvent(UUID.randomUUID().toString(), new Random().nextInt()%100)))
        .then(Mono.fromSupplier(() -> new MemberRegisteredEvent(UUID.randomUUID().toString(), new Random().nextInt()%100))
            .delayElement(Duration.ofMillis(400)));
    }
}
