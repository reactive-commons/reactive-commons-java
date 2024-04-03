package sample;

import lombok.extern.java.Log;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@Log
@EnableDomainEventBus
@EnableDirectAsyncGateway
@SpringBootApplication
public class EDASampleSenderApp {

    public static void main(String[] args) {
        SpringApplication.run(EDASampleSenderApp.class, args);
    }
}
