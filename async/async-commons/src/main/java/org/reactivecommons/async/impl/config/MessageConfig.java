package org.reactivecommons.async.impl.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;


@Configuration
//@Import(RabbitMqConfig.class)
public class MessageConfig {

    @Value("${spring.application.name}")
    private String appName;

    @Value("${app.async.domain.events.exchange:domainEvents}")
    private String domainEventsExchangeName;

    @Value("${app.async.direct.exchange:directMessages}")
    private String directMessagesExchangeName;


}
