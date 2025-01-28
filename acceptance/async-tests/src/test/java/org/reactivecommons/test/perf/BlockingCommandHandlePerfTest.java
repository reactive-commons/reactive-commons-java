package org.reactivecommons.test.perf;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.DomainCommandHandler;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableMessageListeners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static java.lang.System.out;
import static org.assertj.core.api.Assertions.assertThat;
import static reactor.core.publisher.Mono.fromRunnable;

@SpringBootTest
class BlockingCommandHandlePerfTest {

    private static final String COMMAND_NAME = "simpleTestCommand1";

    @Autowired
    private DirectAsyncGateway gateway;

    @Value("${spring.application.name}")
    private String appName;

    private final String commandId = ThreadLocalRandom.current().nextInt() + "";
    private final Long data = ThreadLocalRandom.current().nextLong();

    @Test
    void commandShouldBeHandledInParallel() throws InterruptedException {
        Sinks.Many<Command<Long>> listener = Sinks.many().unicast().onBackpressureBuffer();

        Flux.range(0, 12).flatMap(i -> {
            Command<Long> command = new Command<>(COMMAND_NAME, commandId + 1, data + 1);
            return gateway.sendCommand(command, appName);
        }).subscribe();

        final long init = System.currentTimeMillis();

        final Flux<Command<Long>> results = listener.asFlux().take(12).collectList()
                .timeout(Duration.ofMillis(1500))
                .flatMapMany(Flux::fromIterable);

        StepVerifier.create(results).assertNext(cmd -> {
                    assertThat(cmd.getName()).isEqualTo(COMMAND_NAME);
                })
                .expectNextCount(11)
                .verifyComplete();

        final long total = System.currentTimeMillis() - init;
        out.println("Test duration: " + total);
        assertThat(total).isLessThan(2500L);

        //Give some time to finish messages ack
        Thread.sleep(350);
    }


    @SpringBootApplication
    @EnableDirectAsyncGateway
    @EnableMessageListeners
    static class App {
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }

        @Bean
        public HandlerRegistry registry(Sinks.Many<Command<Long>> listener) {
            return HandlerRegistry.register()
                    .handleCommand(COMMAND_NAME, handle(listener), Long.class);
        }

        @Bean
        public Sinks.Many<Command<Long>> listener() {
            return Sinks.many().unicast().onBackpressureBuffer();
        }

        private DomainCommandHandler<Long> handle(Sinks.Many<Command<Long>> listener) {
            return command -> fromRunnable(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(750);
                    listener.emitNext(command, Sinks.EmitFailureHandler.FAIL_FAST);
                } catch (InterruptedException ignored) {
                }
                listener.emitNext(command, Sinks.EmitFailureHandler.FAIL_FAST);
            });
        }
    }
}
