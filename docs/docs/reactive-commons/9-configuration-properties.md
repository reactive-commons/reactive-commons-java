---
sidebar_position: 8
---

# Configuration Properties

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

<Tabs>
  <TabItem value="rabbitmq" label="RabbitMQ" default>

You can customize some predefined variables of Reactive Commons

This can be done by Spring Boot `application.yaml` or by overriding
the [AsyncProps](https://github.com/reactive-commons/reactive-commons-java/blob/master/starters/async-rabbit-starter/src/main/java/org/reactivecommons/async/rabbit/config/props/AsyncProps.java)
bean.

```yaml
app:
  async:
    app: # this is the name of the default domain
      withDLQRetry: false # if you want to have dlq queues with retries you can set it to true, you cannot change it after queues are created, because you will get an error, so you should delete topology before the change.
      maxRetries: -1 # -1 will be considered default value. When withDLQRetry is true, it will be retried 10 times. When withDLQRetry is false, it will be retried indefinitely.
      retryDelay: 1000 # interval for message retries, with and without DLQRetry
      listenReplies: true # if you will not use ReqReply patter you can set it to false
      createTopology: true # if your organization have restrictions with automatic topology creation you can set it to false and create it manually or by your organization process.
      delayedCommands: false # Enable to send a delayed command to an external target
      prefetchCount: 250 # is the maximum number of in flight messages you can reduce it to process less concurrent messages, this settings acts per instance of your service
      flux:
        maxConcurrency: 250 # max concurrency of listener flow
      domain:
        ignoreThisListener: false # Allows you to disable event listener for this specific domain
        events:
          exchange: domainEvents # you can change the exchange, but you should do it in all applications consistently
          eventsSuffix: subsEvents # events queue name suffix, name will be like ${spring.application.name}.${app.async.domain.events.eventsSuffix}
          notificationSuffix: notification # notification events queue name suffix
      direct:
        exchange: directMessages # you can change the exchange, but you should do it in all applications
        querySuffix: query # queries queue name suffix, name will be like ${spring.application.name}.${app.async.direct.querySuffix}
        commandSuffix: '' # commands queue name suffix, name will be like ${spring.application.name}.${app.async.direct.querySuffix} or ${spring.application.name} if empty by default
        discardTimeoutQueries: false # enable to discard this condition
      global:
        exchange: globalReply # you can change the exchange, but you should do it in all applications
        repliesSuffix: replies # async query replies events queue name suffix
      connectionProperties: # you can override the connection properties of each domain
        host: localhost
        port: 5672
        username: guest
        password: guest
        virtual-host: /
    # Another domain can be configured with same properties structure that app
    accounts: # this is a second domain name and can have another independent setup
      connectionProperties: # you can override the connection properties of each domain
        host: localhost
        port: 5672
        username: guest
        password: guest
        virtual-host: /accounts
```

You can override this settings programmatically through a `AsyncPropsDomainProperties` bean.

```java
package sample;

import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncRabbitPropsDomainProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@Configuration
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
```

Additionally, if you want to set only connection properties you can use the `AsyncPropsDomain.SecretFiller` class.

```java

@Bean
@Primary
public AsyncPropsDomain.SecretFiller customFiller() {
    return (domain, asyncProps) -> {
        // customize asyncProps here by domain
    };
}
```

  </TabItem>
  <TabItem value="kafka" label="Kafka">
    You can customize some predefined variables of Reactive Commons

This can be done by Spring Boot `application.yaml` or by overriding
the [AsyncKafkaProps](https://github.com/reactive-commons/reactive-commons-java/blob/master/starters/async-kafka-starter/src/main/java/org/reactivecommons/async/kafka/config/props/AsyncKafkaProps.java)
bean.

```yaml
reactive:
  commons:
    kafka:
      app: # this is the name of the default domain
        withDLQRetry: false # if you want to have dlq queues with retries you can set it to true, you cannot change it after queues are created, because you will get an error, so you should delete topology before the change.
        maxRetries: -1 # -1 will be considered default value. When withDLQRetry is true, it will be retried 10 times. When withDLQRetry is false, it will be retried indefinitely.
        retryDelay: 1000 # interval for message retries, with and without DLQRetry
        checkExistingTopics: true # if you don't want to verify topic existence before send a record you can set it to false
        createTopology: true # if your organization have restrictions with automatic topology creation you can set it to false and create it manually or by your organization process.
        domain:
          ignoreThisListener: false # Allows you to disable event listener for this specific domain
        connectionProperties: # you can override the connection properties of each domain
          bootstrap-servers: localhost:9092
      # Another domain can be configured with same properties structure that app
      accounts: # this is a second domain name and can have another independent setup
        connectionProperties: # you can override the connection properties of each domain
          bootstrap-servers: localhost:9093
```

You can override this settings programmatically through a `AsyncKafkaPropsDomainProperties` bean.

```java
package sample;

import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.config.props.AsyncProps;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomainProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@Configuration
public class MyDomainConfig {

    @Bean
    @Primary
    public AsyncKafkaPropsDomainProperties customKafkaDomainProperties() {
        KafkaProperties propertiesApp = new KafkaProperties();
        propertiesApp.setBootstrapServers(List.of("localhost:9092"));

        KafkaProperties propertiesAccounts = new KafkaProperties();
        propertiesAccounts.setBootstrapServers(List.of("localhost:9093"));

        return AsyncKafkaPropsDomainProperties.builder()
                .withDomain("app", AsyncProps.builder()
                        .connectionProperties(propertiesApp)
                        .build())
                .withDomain("accounts", AsyncProps.builder()
                        .connectionProperties(propertiesAccounts)
                        .build())
                .build();
    }
}
```

Additionally, if you want to set only connection properties you can use the `AsyncKafkaPropsDomain.KafkaSecretFiller`
class.

```java

@Bean
@Primary
public AsyncKafkaPropsDomain.KafkaSecretFiller customKafkaFiller() {
    return (domain, asyncProps) -> {
        // customize asyncProps here by domain
    };
}
```

  </TabItem>
</Tabs>