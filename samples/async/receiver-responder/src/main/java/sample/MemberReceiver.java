package sample;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import us.sofka.commons.reactive.async.CommandHandler;

import java.time.Duration;
import java.util.Random;
import java.util.UUID;

@Component
public class MemberReceiver implements CommandHandler<MemberRegisteredEvent, AddMemberCommand> {

    @Override
    public Mono<MemberRegisteredEvent> handle(AddMemberCommand command) {
        if(new Random().nextInt()%2 == 0){
            System.out.println("Causando error!!");
            throw new RuntimeException("Error Causado");
        }
        return Mono.fromSupplier(() -> new MemberRegisteredEvent(UUID.randomUUID().toString(), new Random().nextInt()%100))
            .delayElement(Duration.ofMillis(400));
    }
}
