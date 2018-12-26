package org.reactivecommons.test;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableMessageListeners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static reactor.core.publisher.Mono.*;

@SpringBootTest
@RunWith(SpringRunner.class)
public class DirectAsyncGatewayTest {

    @Autowired
    private DirectAsyncGateway gateway;

    @Value("${spring.application.name}")
    private String appName;

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
        public HandlerRegistry registry() {
            return HandlerRegistry.register().serveQuery("double", rqt -> just(rqt*2), Long.class);
        }
    }
}
