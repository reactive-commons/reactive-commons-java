package org.reactivecommons.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.Envelope;
import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.CommandHandler;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableMessageListeners;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.UnicastProcessor;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.core.publisher.Flux.range;
import static reactor.core.publisher.Mono.empty;
import static reactor.core.publisher.Mono.just;

@SpringBootTest
@RunWith(SpringRunner.class)
public class CommandsProcessPerfTest {

    private static final String COMMAND_NAME = "app.command.test";
    private static final int messageCount = 40000;
    private static final Semaphore semaphore = new Semaphore(0);
    private static final CountDownLatch latch = new CountDownLatch(12 + 1);

    @Autowired
    private DirectAsyncGateway gateway;

    @Value("${spring.application.name}")
    private String appName;


    @Test
    public void commandShouldArrive() throws InterruptedException {
        final long init_p = System.currentTimeMillis();
        createMessages(messageCount);
        final long end_p = System.currentTimeMillis() - init_p;
        System.out.println("Total Publication Time: " + end_p + "ms");

        latch.countDown();
        final long init = System.currentTimeMillis();
        semaphore.acquire(messageCount);
        final long end = System.currentTimeMillis();

        final long total = end - init;
        final double microsPerMessage =  ((total+0.0)/messageCount)*1000;
        System.out.println("Message count: " + messageCount);
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per message: " + microsPerMessage + "us");
        Assertions.assertThat(microsPerMessage).isLessThan(140);
    }


    private void createMessages(int count) throws InterruptedException {
        Flux.range(0, count).flatMap(value -> {
            Command<DummyMessage> command = new Command<>("app.command.test", UUID.randomUUID().toString(), new DummyMessage());
            return gateway.sendCommand(command, appName).doOnSuccess(_v -> semaphore.release()).thenReturn(value);
        }).subscribe();

        System.out.println("Wait for publish");
        semaphore.acquire(count);
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
            final HandlerRegistry registry = range(0, 20).reduce(HandlerRegistry.register(), (r, i) -> r.handleCommand("app.command.name" + i, message -> Mono.empty(), Map.class)).block();
            return registry
                .handleCommand(COMMAND_NAME, this::handleSimple, DummyMessage.class);
        }

        private Mono<Void> handleSimple(Command<DummyMessage> message) {
            return Mono.fromRunnable(() -> {
                if (latch.getCount() > 0) {
                    latch.countDown();
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                semaphore.release();
            });
        }

    }
}
