---
sidebar_position: 2
---

# Two Brokers same Broker Type - Emit to external Broker

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import ThemeImage from '../../src/components/ThemeImage';

<ThemeImage scenario="2"></ThemeImage>

`App 1` has only connection to `Broker 1`, which is considered the `app` domain for this app. This is the same as
the [Single Broker](./1-single-broker.md) scenario.

`App 3` has only connection to `Broker 2`, which is considered the `app domain` for this app. This is the same as
the [Single Broker](./1-single-broker.md) scenario.

`App 2` has two brokers, `Broker 1` and `Broker 2`. `Broker 1` is considered the `app` domain for this app, and `Broker 2`
is considered an external broker which will be called `accounts` domain for this scenario.

So you need to configure the connection properties for each broker.

In this scenario, `Broker 1` is considered the `app` domain by default, so `App 2` can listen and send all operations
from/to this broker, but it can only publish commands and queries to `Broker 2` and listen for events from the
`Broker 2` (`accounts` domain).

Note: `App 2` cannot listen notification events, cannot listen for queries, and commands from `Broker 2`. This is for
responsibility segregation.

To send commands and queries to `Broker 2` you need to use the `DirectAsyncGateway` interface with the `accounts` domain
, and the same when listen, you should pass the domain name.

### Listen from external domain

```java
@Bean
@Primary
public HandlerRegistry handlerRegistrySubs(UseCase useCase) {
    return HandlerRegistry.register()
            //.serveQuery(...)
            //.handleCommand(...)
            //.listenEvent(...)
            //.listenNotificationEvent(...)
            .listenDomainEvent("accounts", "event-name", handler::process, MyEventData.class)
            .listenDomainCloudEvent("accounts", "event-name", handler::processCloudEvent);
}
```

### Send to external domain

```java
@Service
@AllArgsConstructor
public class SampleRestController {
    private final DirectAsyncGateway directAsyncGateway;

    public Mono<Teams> getTeams() {
        AsyncQuery<Request> query = ....
        return directAsyncGateway.requestReply(query, "external-service", Teams.class, "accounts");
    }

    public Mono<Teams> getTeamsCloudEvent() {
        CloudEvent query = ....
        return directAsyncGateway.requestReply(query, "external-service", CloudEvent.class, "accounts")
                .map(...);
    }
}
```


Next are configurations needed to set up this scenario for `App 2`.

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
      useDiscardNotifierPerDomain: false # if true it uses a discard notifier for each domain,when false it uses a single discard notifier for all domains with default 'app' domain
      enabled: true # if you want to disable this domain you can set it to false
      brokerType: "rabbitmq" # please don't change this value
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
        port: 5673
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
        RabbitProperties propertiesApp = new RabbitProperties();  // this may be loaded from secrets
        propertiesApp.setHost("localhost");
        propertiesApp.setPort(5672);
        propertiesApp.setVirtualHost("/");
        propertiesApp.setUsername("guest");
        propertiesApp.setPassword("guest");

        RabbitProperties propertiesAccounts = new RabbitProperties(); // this may be loaded from secrets
        propertiesAccounts.setHost("localhost");
        propertiesAccounts.setPort(5673);
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
        useDiscardNotifierPerDomain: false # if true it uses a discard notifier for each domain,when false it uses a single discard notifier for all domains with default 'app' domain
        enabled: true # if you want to disable this domain you can set it to false
        brokerType: "kafka" # please don't change this value
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
        KafkaProperties propertiesApp = new KafkaProperties();  // this may be loaded from secrets
        propertiesApp.setBootstrapServers(List.of("localhost:9092"));
        
        KafkaProperties propertiesAccounts = new KafkaProperties();  // this may be loaded from secrets
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