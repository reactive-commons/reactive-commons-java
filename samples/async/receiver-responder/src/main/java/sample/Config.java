package sample;


import com.rabbitmq.client.ConnectionFactory;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.springframework.context.annotation.Bean;

public class Config {

    @Bean
    public ConnectionFactoryProvider sampleBeanWhenProgrammaticConfiguration() {
        return ConnectionFactory::new;
    }
}
