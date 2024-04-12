package sample;

import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MyRabbitMQConfig {

    @Bean
    @Primary
    public RabbitProperties customRabbitProperties(){
        RabbitProperties properties = new RabbitProperties();
        properties.setHost("localhost");
        properties.setPort(5672);
        properties.setVirtualHost("/");
        properties.setUsername("guest");
        properties.setPassword("guest");
        return properties;
    }
}
