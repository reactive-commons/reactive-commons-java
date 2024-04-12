package sample;

import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomainProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

//@Configuration
public class MyDomainConfig {

    @Bean
    @Primary
    public AsyncPropsDomainProperties customDomainProperties() {
        RabbitProperties propertiesApp = new RabbitProperties();
        propertiesApp.setHost("localhost");
        propertiesApp.setPort(5672);
        propertiesApp.setVirtualHost("/");
        propertiesApp.setUsername("guest");
        propertiesApp.setPassword("guest");

        RabbitProperties propertiesAccounts = new RabbitProperties();
        propertiesAccounts.setHost("localhost");
        propertiesAccounts.setPort(5672);
        propertiesAccounts.setVirtualHost("/accounts");
        propertiesAccounts.setUsername("guest");
        propertiesAccounts.setPassword("guest");

        return AsyncPropsDomainProperties.builder()
                .withDomain("app", AsyncProps.builder()
                        .connectionProperties(propertiesApp)
                        .build())
                .withDomain("accounts", AsyncProps.builder()
                        .connectionProperties(propertiesAccounts)
                        .build())
                .build();
    }
}
