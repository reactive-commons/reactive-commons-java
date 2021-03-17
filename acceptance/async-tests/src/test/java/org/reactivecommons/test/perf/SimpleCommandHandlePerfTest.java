package org.reactivecommons.test.perf;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.CommandHandler;
import org.reactivecommons.async.rabbit.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.rabbit.config.annotations.EnableMessageListeners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.UnicastProcessor;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static java.lang.System.out;
import static org.assertj.core.api.Assertions.assertThat;
import static reactor.core.publisher.Mono.delay;

@SpringBootTest
public class SimpleCommandHandlePerfTest {

    private static final String COMMAND_NAME = "simpleTestCommand3";

    @Autowired
    private DirectAsyncGateway gateway;

    @Value("${spring.application.name}")
    private String appName;

    @Autowired
    private UnicastProcessor<Command<Long>> listener;

    private String commandId = ThreadLocalRandom.current().nextInt() + "";
    private Long data = ThreadLocalRandom.current().nextLong();

    @Test
    @Disabled
    public void commandShouldBeHandledInParallel() {
        Flux.range(0, 30).flatMap(i -> {
            Command<Long> command = new Command<>(COMMAND_NAME, commandId + 1, data + 1);
            return gateway.sendCommand(command, appName);
        }).blockLast();

        final long init = System.currentTimeMillis();

        final Flux<Command<Long>> results = listener.take(30).collectList()
                .timeout(Duration.ofMillis(3500))
                .flatMapMany(Flux::fromIterable);

        StepVerifier.create(results).assertNext(cmd -> {
            assertThat(cmd.getName()).isEqualTo(COMMAND_NAME);
        })
                .expectNextCount(29)
                .verifyComplete();

        final long total = System.currentTimeMillis() - init;
        out.println("Test duration: " + total);
        assertThat(total).isLessThan(3500);
    }


    @SpringBootApplication
    @EnableDirectAsyncGateway
    @EnableMessageListeners
//    @PropertySource("application2.properties")
    static class App {
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }

        @Bean
        public HandlerRegistry registry(UnicastProcessor<Command<Long>> listener) {
            return HandlerRegistry.register()
                    .handleCommand(COMMAND_NAME, handle(listener), Long.class);
        }

        @Bean
        public UnicastProcessor<Command<Long>> listener() {
            return UnicastProcessor.create();
        }

        private CommandHandler<Long> handle(UnicastProcessor<Command<Long>> listener) {
            return command -> {
                out.println("Received at: " + System.currentTimeMillis() / 1000);
                out.println("Received in: " + Thread.currentThread().getName());
                return delay(Duration.ofMillis(750)).doOnNext(i -> {
                    out.println("Handled at: " + System.currentTimeMillis() / 1000);
                    listener.onNext(command);
                }).then();
            };
        }
    }
}
