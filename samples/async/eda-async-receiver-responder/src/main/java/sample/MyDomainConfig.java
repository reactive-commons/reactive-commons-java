package sample;

import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

//@Configuration
public class MyDomainConfig {

    @Bean
    @Primary
    public AsyncPropsDomain.RabbitPropsCustomizer rabbitPropsCustomizer() {
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

        return domainProperties -> {
            // Customize the "app" domain — YAML values are kept, only these fields are overridden
            domainProperties.customize(
                    "app", app -> app.setConnectionProperties(propertiesApp)
            );

            // Customize the "accounts" domain independently
            domainProperties.customize(
                    "accounts", accounts -> accounts.setConnectionProperties(propertiesAccounts)
            );
        };
    }
}
