package sample;

import lombok.extern.java.Log;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@EnableDirectAsyncGateway
@SpringBootApplication
@Log
public class SampleSenderApp {

    public static void main(String[] args) throws InterruptedException {
        SpringApplication.run(SampleSenderApp.class, args);
        Thread.sleep(Long.MAX_VALUE);
    }

    @Bean
    public CommandLineRunner run(MemberRegistrySender sender, DirectAsyncGateway directAsyncGateway) {
        return args -> {
            Flux.interval(Duration.ofSeconds(1)).concatMap(n -> {
                AddMemberCommand command = new AddMemberCommand("Daniel " + n, n + "");
                return Mono.defer(() -> sender.registerMember(command));
            }).doOnError(t -> log.warning(t.getMessage()))
                    .retry()
                    .subscribe(event -> {
                        log.info("Registered Event");
                        log.info(event.getMemberId());
                        log.info(event.getInitialScore() + " Score");
                    });

            directAsyncGateway.requestReply(new AsyncQuery<>("serveQuery.empty", "test"), "Receiver2", String.class)
                    .defaultIfEmpty("empty!")
                    .subscribe(result -> System.out.println("Arrived " + result));
        };
    }
}
