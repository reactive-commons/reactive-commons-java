package sample;

import com.rabbitmq.client.ConnectionFactory;
import lombok.Builder;
import lombok.Data;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableMessageListeners;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;

import static reactor.core.publisher.Mono.delay;
import static reactor.core.publisher.Mono.just;

@SpringBootApplication
@EnableMessageListeners
@EnableDomainEventBus
@EnableDirectAsyncGateway
@Log
public class SampleReceiverApp {
    public static void main(String[] args) {
        SpringApplication.run(SampleReceiverApp.class, args);
    }

    private ConnectionRabbitProperties rabbitProperties(){
        return ConnectionRabbitProperties.builder()
            .hostname("b-8b1e2880-30bd-4124-b765-220854289b87.mq.us-east-1.amazonaws.com")
            .username("user")
            .password("userrszthwco")
            .port(5671)
            .virtualhost("/")
            .ssl(true)
            .build();
    }

    private void configureSsl(ConnectionFactory connectionFactory) {
        try {
            connectionFactory.useSslProtocol();
        } catch (NoSuchAlgorithmException | KeyManagementException exception) {
            log.log(Level.SEVERE, exception.getMessage(), exception);
        }
    }

    @Bean
    public CommandLineRunner runner(DirectAsyncGateway gateway) {
        return args -> {
            Flux.interval(Duration.ofSeconds(3)).flatMap(l -> {
                AsyncQuery<Map> query = new AsyncQuery<>("query", Map.of("type", "query"));
                return gateway.requestReply(query, "", Map.class);
            });

        };
    }

    @Bean
    public HandlerRegistry registry() {
        return HandlerRegistry.register()
            .serveQuery("query", message -> delay(Duration.ofMillis(500)).thenReturn(message), Map.class)
            .handleCommand("command", message -> just(message).log().then(), Map.class)
            .listenEvent("event", message -> just(message).log().then(), Map.class)
            .listenNotificationEvent("event1", message -> just(message).log().then(), Map.class)
            ;
    }

    @Bean
    @Primary
    public ConnectionFactoryProvider connection(){
        ConnectionRabbitProperties properties = rabbitProperties();
        final ConnectionFactory factory = new ConnectionFactory();
        PropertyMapper map = PropertyMapper.get();
        map.from(properties::getHostname).whenNonNull().to(factory::setHost);
        map.from(properties::getPort).to(factory::setPort);
        map.from(properties::getUsername).whenNonNull().to(factory::setUsername);
        map.from(properties::getPassword).whenNonNull().to(factory::setPassword);
        map.from(properties::getVirtualhost).whenNonNull().to(factory::setVirtualHost);
        map.from(properties::isSsl).whenTrue().as(ssl -> factory).to(this::configureSsl);
        System.out.println("RabbitMQ configured!!");
        return () -> factory;
    }

    @Data
    @Builder
    static class ConnectionRabbitProperties {
        private String virtualhost;
        private String hostname;
        private String username;
        private String password;
        private Integer port;
        private boolean ssl;
    }
}