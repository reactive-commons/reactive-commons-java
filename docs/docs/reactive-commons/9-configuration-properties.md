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
      useDiscardNotifierPerDomain: false # if true it uses a discard notifier for each domain,when false it uses a single discard notifier for all domains with default 'app' domain
      enabled: true # if you want to disable this domain you can set it to false
      mandatory: false # if you want to enable mandatory messages, you can set it to true, this will throw an exception if the message cannot be routed to any queue
      brokerType: "rabbitmq" # please don't change this value
      queueType: null # you can set to 'classic' or to 'quorum' if your RabbitMQ cluster supports it, by default it will take the virtual host default queue type
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

You can override this settings programmatically through a `AsyncRabbitPropsDomainProperties` bean.

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
    public AsyncRabbitPropsDomainProperties customDomainProperties() {
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

        return AsyncRabbitPropsDomainProperties.builder()
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

## Loading properties from a secret

Additionally, if you want to set only connection properties you can use the `AsyncPropsDomain.RabbitSecretFiller` class.

```java

@Bean
public AsyncPropsDomain.RabbitSecretFiller customFiller() {
    return (domain, asyncProps) -> {
        // customize asyncProps here by domain
    };
}
```

For example if you use the [Secrets Manager](https://github.com/bancolombia/secrets-manager) project, you may use
the next code to load the properties from a secret:

1. Create a class with the properties that you will load from the secret:

```java
import lombok.Builder;
import lombok.Data;
import org.reactivecommons.async.rabbit.config.RabbitProperties;

@Data
@Builder
public class RabbitConnectionProperties {
    private String hostname;
    private String password;
    private String username;
    private Integer port;
    private String virtualhost;
    private boolean ssl;

    public RabbitProperties toRabbitProperties() {
        var rabbitProperties = new RabbitProperties();
        rabbitProperties.setHost(this.hostname);
        rabbitProperties.setUsername(this.username);
        rabbitProperties.setPassword(this.password);
        rabbitProperties.setPort(this.port);
        rabbitProperties.setVirtualHost(this.virtualhost);
        rabbitProperties.getSsl().setEnabled(this.ssl); // To enable SSL
        return rabbitProperties;
    }
}
```

2. Use the `SecretsManager` to load the properties from the secret:

```java
import co.com.bancolombia.secretsmanager.api.GenericManager;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.GenericArrayType;

@Log4j2
@Configuration
public class AsyncEventBusConfig {

    // TODO: You should create the GenericManager bean as indicated in Secrets Manager library
    @Bean
    public AsyncPropsDomain.RabbitSecretFiller rabbitSecretFiller(GenericManager genericManager) {
        return (domain, props) -> {
            if (props.getSecret() != null) {
                log.info("Loading RabbitMQ connection properties from secret: {}", props.getSecret());
                props.setConnectionProperties(getFromSecret(genericManager, props.getSecret()));
                log.info("RabbitMQ connection properties loaded successfully with host: '{}'",
                        props.getConnectionProperties().getHost());
            }
        };
    }

    @SneakyThrows
    private RabbitProperties getFromSecret(GenericManager genericManager, String secretName) {
        return genericManager.getSecret(secretName, RabbitConnectionProperties.class).toRabbitProperties();
    }
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

## Mandatory property in RabbitMQ

The mandatory property is a message publishing parameter in RabbitMQ that determines the behavior when a message cannot
be routed to any queue. This can happen if there are no queues bound to the exchange or if the routing key does not
match any of the available queues.

By default, this option is disabled, but if activated (`mandatory = true`), it works right after the message is
published to an exchange, but before it is routed to a queue.

When a message is published with `mandatory = true`, RabbitMQ will try to route it from the exchange to one or more
queues. If no queue receives the message, then:

- The message is not lost, but it is not delivered to any queue.
- RabbitMQ triggers a basic.return event on the producer's channel.
- The producer must have a ReturnListener or an equivalent handler to receive and process the returned message. If one
  is not defined, the message is lost.

#### Example

Assuming we have:

- A direct type exchange.
- A queue bound with the key `order.created`.
- A message is published with the key `order.cancelled` and `mandatory = true`.

Result:

- If there is no queue bound with `order.cancelled`, the message is not routed.
- Since `mandatory = true`, RabbitMQ tries to return it to the producer.
- If there is a ReturnListener, this message can be captured and handled, for example, by sending it to another
- consumer's queue, DLQ queues, saving it in a log file, or in a database.

### Advantages

- Early detection of routing errors: Prevents critical messages from "disappearing" without a trace, which facilitates
  the identification of erroneous configurations in bindings or patterns.
- Integrity and reliability: Ensures that each message finds a consumer or, failing that, returns to the producer for
  alternative handling (DLQ queues, logs, database).
- Operational visibility: Facilitates metrics of "unrouted messages" and alerts when the event flow does not follow the
  planned routes.

### Considerations

Although this property does not prevent performance problems or degradation of the RabbitMQ cluster, it is useful for
preventing the loss of unrouted messages and for detecting configuration errors in routing.

When mandatory is active, under normal conditions (all routes exist), there is practically no impact. In anomalous
situations, there will be additional return traffic for each unroutable message. This implies an extra load for both
RabbitMQ (which must send the message back to the producer) and the sending application (which must process the returned
message).

### Implementation

To enable the `mandatory` property in Reactive Commons, you can configure it in your project's `application.yaml` file:

```yaml
app:
  async:
    app: # this is the name of the default domain
      mandatory: true # enable mandatory property
```

Now we configure the return handler to manage messages that could not be delivered correctly. By default, these messages
are displayed in a log.
To customize this behavior, a class that implements the `UnroutableMessageHandler` interface is created and registered
as a Spring bean:

```java
package sample;

import co.com.mypackage.usecase.MyUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.rabbit.communications.MyOutboundMessage;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessageResult;

import java.nio.charset.StandardCharsets;

@Log
@Component
@RequiredArgsConstructor
public class ResendUnroutableMessageHandler implements UnroutableMessageHandler {

    private final MyUseCase useCase;

    @Override
    public Mono<Void> processMessage(OutboundMessageResult<MyOutboundMessage> result) {
        var returned = result.getOutboundMessage();
        log.severe("Unroutable message: exchange=" + returned.getExchange()
                + ", routingKey=" + returned.getRoutingKey()
                + ", body=" + new String(returned.getBody(), StandardCharsets.UTF_8)
                + ", properties=" + returned.getProperties()
        );

        // Process the unroutable message
        return useCase.sendMessage(new String(returned.getBody(), StandardCharsets.UTF_8));
    }
}
```

#### Send unrouted messages to a queue

To send the unrouted message to a queue, we use the `@EnableDomainEventBus` annotations for
[domain events](/reactive-commons-java/docs/reactive-commons/sending-a-domain-event), and `@EnableDirectAsyncGateway`
for [commands](/reactive-commons-java/docs/reactive-commons/sending-a-command) and
[asynchronous queries](/reactive-commons-java/docs/reactive-commons/making-an-async-query), as appropriate.

It is important to ensure that the queue exists before sending the message, as it will otherwise be lost.
Therefore, it is recommended to verify or create the queue beforehand to ensure successful delivery.

```java
package sample;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.rabbit.communications.MyOutboundMessage;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;

@Component
@EnableDirectAsyncGateway
public class ResendUnroutableMessageHandler implements UnroutableMessageHandler {

    private final ObjectMapper objectMapper;
    private final String retryQueueName;
    private final DirectAsyncGateway gateway;

    public ResendUnroutableMessageHandler(
            ObjectMapper objectMapper,
            DirectAsyncGateway gateway,
            @Value("${adapters.rabbitmq.retry-queue-name}") String retryQueueName) {
        this.objectMapper = objectMapper;
        this.retryQueueName = retryQueueName;
        this.gateway = gateway;
    }

    public Mono<Void> emitCommand(String name, String commandId, Object data) {
        return Mono.from(gateway.sendCommand(
                // Connection with broker using the properties defined through the
                // AsyncRabbitPropsDomainProperties bean with the "logs" domain
                new Command<>(name, commandId, data), retryQueueName, "logs")
        );
    }

    @Override
    public Mono<Void> processMessage(OutboundMessageResult<MyOutboundMessage> result) {
        OutboundMessage returned = result.getOutboundMessage();
        try {
            // The unroutable message is a command, so the message body is deserialized to the Command class.
            // Use the DomainEvent class for domain events and the AsyncQuery class for asynchronous queries.
            Command<JsonNode> command = objectMapper.readValue(returned.getBody(), new TypeReference<>() {
            });

            // Send the message to the queue
            return emitCommand(command.getName(), command.getCommandId(), command.getData())
                    .doOnError(e -> log.severe("Failed to send the returned message: " + e.getMessage()));
        } catch (Exception e) {
            log.severe("Error deserializing the returned message: " + e.getMessage());
            return Mono.empty();
        }
    }
}
```

In the RabbitMQ configuration class, we create the `UnroutableMessageProcessor` bean to register the unrouted message
handler.

```java
package sample;

import org.reactivecommons.async.rabbit.communications.UnroutableMessageNotifier;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageProcessor;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncRabbitPropsDomainProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration("rabbitMQConfiguration")
public class RabbitMQConfig {

    private final RabbitMQConnectionProperties properties;
    private final RabbitMQConnectionProperties propertiesLogs;
    private final Boolean withDLQRetry;
    private final Integer maxRetries;
    private final Integer retryDelay;

    public RabbitMQConfig(@Qualifier("rabbit") RabbitMQConnectionProperties properties,
                          @Qualifier("rabbitLogs") RabbitMQConnectionProperties propertiesLogs,
                          @Value("${adapters.rabbitmq.withDLQRetry}") Boolean withDLQRetry,
                          @Value("${adapters.rabbitmq.maxRetries}") Integer maxRetries,
                          @Value("${adapters.rabbitmq.retryDelay}") Integer retryDelay) {
        this.properties = properties;
        this.propertiesLogs = propertiesLogs;
        this.withDLQRetry = withDLQRetry;
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
    }

    // This bean is used to create the RabbitMQ connection properties for the application
    @Bean
    @Primary
    public AsyncRabbitPropsDomainProperties customDomainProperties() {
        var propertiesApp = new RabbitProperties();
        propertiesApp.setHost(properties.hostname());
        propertiesApp.setPort(properties.port());
        propertiesApp.setVirtualHost(properties.virtualhost());
        propertiesApp.setUsername(properties.username());
        propertiesApp.setPassword(properties.password());
        propertiesApp.getSsl().setEnabled(properties.ssl());

        var propertiesLogs = new RabbitProperties();
        propertiesLogs.setHost(propertiesDual.hostname());
        propertiesLogs.setPort(propertiesDual.port());
        propertiesLogs.setVirtualHost(propertiesDual.virtualhost());
        propertiesLogs.setUsername(propertiesDual.username());
        propertiesLogs.setPassword(propertiesDual.password());
        propertiesLogs.getSsl().setEnabled(propertiesDual.ssl());

        return AsyncRabbitPropsDomainProperties.builder()
                .withDomain("app", AsyncProps.builder()
                        .connectionProperties(propertiesApp)
                        .withDLQRetry(withDLQRetry)
                        .maxRetries(maxRetries)
                        .retryDelay(retryDelay)
                        .mandatory(Boolean.TRUE)
                        .build())
                .withDomain("logs", AsyncProps.builder()
                        .connectionProperties(propertiesLogs)
                        .mandatory(Boolean.TRUE)
                        .build())
                .build();
    }

    // This bean is used to register the handler for unroutable messages
    @Bean
    UnroutableMessageProcessor registerUnroutableMessageHandler(UnroutableMessageNotifier unroutableMessageNotifier,
                                                                ResendUnroutableMessageHandler handler) {
        var factory = new UnroutableMessageProcessor();
        unroutableMessageNotifier.listenToUnroutableMessages(handler);
        return factory;
    }

}
```
