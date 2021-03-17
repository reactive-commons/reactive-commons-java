package org.reactivecommons.test;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
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
import reactor.core.publisher.Mono;
import reactor.core.publisher.UnicastProcessor;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.core.publisher.Mono.*;

@SpringBootTest
public class SimpleDirectCommunicationTest {

    private static final String COMMAND_NAME = "simpleTestCommand";

    @Autowired
    private DirectAsyncGateway gateway;

    @Value("${spring.application.name}")
    private String appName;

    @Autowired
    private UnicastProcessor<Command<Long>> listener;

    private String commandId = ThreadLocalRandom.current().nextInt() + "";
    private Long data = ThreadLocalRandom.current().nextLong();

    @Test
    public void commandShouldArrive() {
        Command<Long> command = new Command<>(COMMAND_NAME, commandId, data);
        gateway.sendCommand(command, appName).subscribe();

        StepVerifier.create(listener.next()).assertNext(cmd -> {
            assertThat(cmd).extracting(Command::getCommandId, Command::getData, Command::getName)
                .containsExactly(commandId, data, COMMAND_NAME);
        }).verifyComplete();
    }

    @Test
    public void shouldReceiveResponse() {
        final Mono<Integer> reply = gateway.requestReply(new AsyncQuery<>("double", 42), appName, Integer.class);
        StepVerifier.create(reply.timeout(Duration.ofSeconds(15)))
            .expectNext(42*2)
            .verifyComplete();
    }


    @SpringBootApplication
    @EnableDirectAsyncGateway
    @EnableMessageListeners
    static class App{
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }

        @Bean
        public HandlerRegistry registry(UnicastProcessor<Command<Long>>  listener) {
            return HandlerRegistry.register()
                .serveQuery("double", rqt -> just(rqt*2), Long.class)
                .handleCommand(COMMAND_NAME, handle(listener), Long.class);
        }

        @Bean
        public UnicastProcessor<Command<Long>> listener() {
            return UnicastProcessor.create();
        }

        private CommandHandler<Long> handle(UnicastProcessor<Command<Long>> listener) {
            return command -> {
                listener.onNext(command);
                return empty();
            };
        }
    }
}
