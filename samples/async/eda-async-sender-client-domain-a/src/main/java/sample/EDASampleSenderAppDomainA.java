package sample;

import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@EnableDomainEventBus
@EnableDirectAsyncGateway
@SpringBootApplication
public class EDASampleSenderAppDomainA {

    public static void main(String[] args) {
        SpringApplication.run(EDASampleSenderAppDomainA.class, args);
    }
}
