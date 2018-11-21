package sample;

import lombok.extern.java.Log;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@SpringBootApplication
@Log
public class SampleSenderApp {

    public static void main(String[] args) {
        SpringApplication.run(SampleSenderApp.class, args);
    }

    @Bean
    public CommandLineRunner run(MemberRegistrySender sender) {
        return args -> {
            Flux.interval(Duration.ofSeconds(1)).concatMap(n -> {
                AddMemberCommand command = new AddMemberCommand("Daniel " + n, n+"");
                return Mono.defer(() -> sender.registerMember(command));
            })  .doOnError(t -> log.warning(t.getMessage()))
                .retry()
                .subscribe(event -> {
                    log.info("Registered Event");
                    log.info(event.getMemberId());
                    log.info(event.getInitialScore() + " Score");
                });
        };
    }
}
