package sample;

import org.reactivecommons.async.impl.config.annotations.EnableCommandListeners;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableEventListeners;
import org.reactivecommons.async.impl.config.annotations.EnableNotificationListener;
import org.reactivecommons.async.impl.config.annotations.EnableQueryListeners;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableEventListeners
@EnableNotificationListener
@EnableQueryListeners
@EnableCommandListeners
@EnableDomainEventBus
@EnableDirectAsyncGateway
public class SampleReceiverApp {
    public static void main(String[] args) {
        SpringApplication.run(SampleReceiverApp.class, args);
    }

}
