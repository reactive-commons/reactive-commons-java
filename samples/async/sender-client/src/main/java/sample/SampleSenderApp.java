package sample;

import lombok.extern.java.Log;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@EnableDirectAsyncGateway
@EnableDomainEventBus
@SpringBootApplication
@Log
public class SampleSenderApp {

    public static void main(String[] args) throws InterruptedException {
        SpringApplication.run(SampleSenderApp.class, args);
        Thread.sleep(Long.MAX_VALUE);
    }

    @Bean
    public CommandLineRunner run(MemberRegistrySender sender, DirectAsyncGateway asyncGateway, DomainEventBus bus) {
        //Command<AddMemberCommand> command0 = new Command<>("test.cmd", "01", new AddMemberCommand("Daniel ", "a"));
        //asyncGateway.sendCommand(command0, "Receiver2").subscribe();

        //asyncGateway.requestReply(new AsyncQuery<>("test.query", new AddMemberCommand("Daniel", "b")), "Receiver2", String.class).subscribe();
        //DomainEvent<AddMemberCommand> event1 = new DomainEvent<>("test.event", "id11", new AddMemberCommand("Daniel", "b"));
        //Mono.from(bus.emit(event1)).subscribe();
        //try {
            //Thread.sleep(Long.MAX_VALUE);
        //} catch (InterruptedException e) {
        //    e.printStackTrace();
        //}
        return args -> Flux.interval(Duration.ofSeconds(1)).concatMap(n -> {
            AddMemberCommand command = new AddMemberCommand("Daniel " + n, n+"");
            return asyncGateway.requestReply(new AsyncQuery<>("serveQuery.empty", "test"), "Receiver2", String.class)
                .doOnNext(s -> log.warning("Should not be called!!"))
                .doOnSuccess(s -> log.info("Empty Completion response!"))
                .then(Mono.defer(() -> sender.registerMember(command)));
        })  .doOnError(t -> log.warning(t.getMessage()))
            .retry()
            .subscribe(event -> {
                log.info("Registered Event");
                log.info(event.getMemberId());
                log.info(event.getInitialScore() + " Score");
            });
    }
}
