package org.reactivecommons.test;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static reactor.core.publisher.Flux.range;

@SpringBootTest
public class QueryProcessPerfTest {

    private static final String QUERY_NAME = "app.command.test";
    private static final int messageCount = 40000;
    private static final Semaphore semaphore = new Semaphore(0);
    private static final AtomicLong atomicLong = new AtomicLong(0);
    private static final CountDownLatch latch = new CountDownLatch(12 + 1);

    @Autowired
    private DirectAsyncGateway gateway;

    @Value("${spring.application.name}")
    private String appName;


    @Test
    public void serveQueryPerformanceTest() throws InterruptedException {
        final Flux<AsyncQuery<DummyMessage>> messages = createMessages(messageCount);

        final long init = System.currentTimeMillis();
        messages
                .flatMap(dummyMessageAsyncQuery -> gateway.requestReply(dummyMessageAsyncQuery, appName, DummyMessage.class)
                        .doOnNext(s -> semaphore.release())
                )
                .subscribe();
        semaphore.acquire(messageCount);
        final long end = System.currentTimeMillis();

        final long total = end - init;
        assertMessageThroughput(total, messageCount, 200);
    }

    private void assertMessageThroughput(long total, long messageCount, int reqMicrosPerMessage) {
        final double microsPerMessage = ((total + 0.0) / messageCount) * 1000;
        System.out.println("Message count: " + messageCount);
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per message: " + microsPerMessage + "us");
        System.out.println("Throughput: " + Math.round(messageCount / (total / 1000.0)) + " Msg/Seg");
        Assertions.assertThat(microsPerMessage).isLessThan(reqMicrosPerMessage);
    }


    private Flux<AsyncQuery<DummyMessage>> createMessages(int count) {
        final List<AsyncQuery<DummyMessage>> queryList = IntStream.range(0, count).mapToObj(_v -> new AsyncQuery<>(QUERY_NAME, new DummyMessage())).collect(Collectors.toList());
        return Flux.fromIterable(queryList);
    }


    @SpringBootApplication
    @EnableDirectAsyncGateway
    @EnableMessageListeners
    static class App {
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }

        @Bean
        public HandlerRegistry registry() {
            final HandlerRegistry registry = range(0, 20).reduce(HandlerRegistry.register(), (r, i) -> r.handleCommand("app.command.name" + i, message -> Mono.empty(), Map.class)).block();
            return registry
                    .serveQuery(QUERY_NAME, this::handleSimple, DummyMessage.class);
        }

        private Mono<DummyMessage> handleSimple(DummyMessage message) {
            message.setAge(message.getAge() + 12);
            return Mono.just(message);
        }


    }
}
