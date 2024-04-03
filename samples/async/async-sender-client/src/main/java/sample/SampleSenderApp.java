package sample;

import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableDirectAsyncGateway
@EnableDomainEventBus
@SpringBootApplication
public class SampleSenderApp {
    public static void main(String[] args) {
        SpringApplication.run(SampleSenderApp.class, args);
    }
}
