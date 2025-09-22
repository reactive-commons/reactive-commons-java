---
sidebar_position: 1
---

# RabbitMQ Configuration

You can customize some predefined variables of Reactive Commons.

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
      listenReplies: null # Allows true or false values. If you're using the ReqReply pattern, set it to true. If you don't, set it to false.
      createTopology: true # if your organization have restrictions with automatic topology creation you can set it to false and create it manually or by your organization process.
      delayedCommands: false # Enable to send a delayed command to an external target
      prefetchCount: 250 # is the maximum number of in flight messages you can reduce it to process less concurrent messages, this setting acts per instance of your service
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

You can override this settings programmatically through an `AsyncRabbitPropsDomainProperties` bean:

:::caution[Mandatory `app` Domain Configuration]
To ensure a correct configuration, you should always override the properties of the `app` domain. If it is not
configured, an exception will be thrown. You can also add properties for additional custom domain if needed.
:::

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
public class RabbitMQConfig {

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

## Customizing the connection

For advanced control over the RabbitMQ connection, you can define a `ConnectionFactoryCustomizer` bean. This allows you
to configure options that are not exposed through standard properties, such as custom timeouts, SSL/TLS settings,
or automatic recovery strategies:

```java

@Bean
public ConnectionFactoryCustomizer connectionFactoryCustomizer() {
    return (ConnectionFactoryCustomizer) (asyncProps, connectionFactory) -> {
        connectionFactory.setExceptionHandler(new MyCustomExceptionHandler()); // Optional custom exception handler
        connectionFactory.setCredentialsProvider(new MyCustomCredentialsProvider()); // Optional custom credentials provider
        return connectionFactory;
    };
}
```

## Connections and channels

Reactive Commons establishes **a single connection to the RabbitMQ broker**, which is reused for all messaging
operations, both sending and listening. However, the number of open **channels** within that connection varies depending
on the enabled annotations and the type of interaction (sending, listening, or both). Each scenario described below
shows how the number of channels changes according to the applied configuration.

In the context of this documentation, a domain refers to a connection with a broker. The configuration supports up to
two brokers, which means the described scenarios are limited to a maximum of two domains.

### Annotations used in the tables

**[1] Annotations for sending messages:**

- `@EnableDomainEventBus` to send [domain events](/reactive-commons-java/docs/reactive-commons/sending-a-domain-event).
- `@EnableDirectAsyncGateway` to send [commands](/reactive-commons-java/docs/reactive-commons/sending-a-command)
  and [asynchronous queries](/reactive-commons-java/docs/reactive-commons/making-an-async-query).

**[2] Annotations for listening to messages:**

- `@EnableEventListeners` to
  listen [domain events](/reactive-commons-java/docs/reactive-commons/handling-domain-events).
- `@EnableCommandListeners` to listen [commands](/reactive-commons-java/docs/reactive-commons/handling-commands).
- `@EnableQueryListeners` to serve [async queries](/reactive-commons-java/docs/reactive-commons/serving-async-queries).

### 1. Sending messages (single domain)

> In this scenario we only use annotations to enable message sending only, along with different configurations for the
`listenReplies` property:

| Enabled annotations        | listenReplies | Broker     | Connections | Channels |
|----------------------------|---------------|------------|-------------|----------|
| One or all for sending [1] | true          | Broker app | 1           | 13       |
|                            | false         | Broker app | 1           | 11       |

### 2. Sending messages (multiple domains)

> In this scenario, we only send messages to two brokers, using one or all of the annotations and configurations for the
`listenReplies` property:

| Enabled annotations        | listenReplies | Broker            | Connections | Channels |
|----------------------------|---------------|-------------------|-------------|----------|
| One or all for sending [1] | true          | Broker app        | 1           | 18       |
|                            |               | Additional broker | 1           | 8        |
| One or all for sending [1] | false         | Broker app        | 1           | 16       |
|                            |               | Additional broker | 1           | 6        |

### 3. Listening for messages (single domain)

> This scenario enables only listening for messages from a single broker, using one or all available annotations:

| Enabled annotations          | Broker     | Connections | Channels |
|------------------------------|------------|-------------|----------|
| One or all for listening [2] | Broker app | 1           | 14       |

### 4. Listening for messages (multiple domains)

> In this scenario, messages are listened to from two brokers, with variations in the annotations enabled:

| Enabled annotations   | Broker            | Connections | Channels |
|-----------------------|-------------------|-------------|----------|
| All for listening [2] | Broker app        | 1           | 19       |
|                       | Additional broker | 1           | 8        |
| Two for listening [2] | Broker app        | 1           | 18       |
|                       | Additional broker | 1           | 8        |
| One for listening [2] | Broker app        | 1           | 17       |
|                       | Additional broker | 1           | 7        |

### 5. Sending and listening for messages (single domain)

> This scenario enables both sending and listening for messages on a single broker, with all annotations enabled:

| Enabled annotations                           | Broker     | Connections | Channels |
|-----------------------------------------------|------------|-------------|----------|
| All for sending [1] and all for listening [2] | Broker app | 1           | 16       |

### 6. Sending and listening for messages (multiple domains)

> In this scenario, messages are sent and listened from two brokers, with variations in the annotations enabled:

| Enabled annotations                           | Broker            | Connections | Channels |
|-----------------------------------------------|-------------------|-------------|----------|
| All for sending [1] and all for listening [2] | Broker app        | 1           | 21       |
|                                               | Additional broker | 1           | 10       |
| One for sending [1] and all for listening [2] | Broker app        | 1           | 20       |
|                                               | Additional broker | 1           | 9        |
| All for sending [1] and two for listening [2] | Broker app        | 1           | 20       |
|                                               | Additional broker | 1           | 10       |
| All for sending [1] and one for listening [2] | Broker app        | 1           | 19       |
|                                               | Additional broker | 1           | 8        |
| All for sending [1]                           | Broker app        | 1           | 16       |
|                                               | Additional broker | 1           | 6        |

### Recommendations

- **Resource Optimization:** If only sending commands and events is required, disabling the `listenReplies` property
  reduces the number of open channels.
- **Selective Annotation Activation:** Enabling only the necessary annotations for the use case can improve performance
  and simplify configuration.
- **Proper Use of Configuration Properties:** Adjusting configuration properties according to the specific use case
  allows for resource optimization and avoids unnecessary configurations.

### Connections in microservices with multiple replicas

In a typical cloud production environment, such as AWS, microservices are deployed in containers orchestrated by
Kubernetes, using managed services like Amazon EKS. For the messaging broker, Amazon MQ for RabbitMQ is used, configured
in a 3-node cluster with a Multi-AZ deployment to ensure high availability and fault tolerance.

When working with microservices that use multiple replicas (instances) and implement Reactive Commons, it is important
to understand how connections to the message broker are managed. Each replica of a microservice establishes **a single
connection** to the broker, which is used for both sending and listening to messages.

The number of open channels within that single connection will depend on the configuration of the annotations used, as
described in the connection scenarios above. This allows each replica to manage its messaging operations independently,
distributing the workload efficiently.

For example, if a microservice is deployed with **4 replicas**, each of them will establish its own connection to the
broker. As a result, the entire microservice deployment will have a total of **4 connections** to the broker.

## Mandatory property

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

### Example

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

To enable the `mandatory` property, you can configure it in your project's `application.yaml` file:

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

@Configuration
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

## Troubleshooting

### PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange'

This error occurs when there is a mismatch between the queue properties defined in your application and the properties
of the queue that already exists in the RabbitMQ broker.
It commonly happens when you try to:

- Change the name of a domain.
- Enable or disable DLQ (Dead Letter Queue) functionality for a queue that has already been created.

**Error log example:**

```text
Caused by: com.rabbitmq.client.ShutdownSignalException: channel error; protocol method:
#method<channel.close>(reply-code=406, reply-text=PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange' 
for queue 'ms_example.query' in vhost '/': received none but current is the value 'directMessages.DLQ' 
of type 'longstr', class-id=50, method-id=10)
```

**Cause:**

RabbitMQ does not allow changing certain durable properties of a queue after it has been declared, such as the
`x-dead-letter-exchange` argument.
When your application starts, it tries to declare the queue with the new properties, but the broker rejects the
declaration because it conflicts
with the existing queue.

**Solution:**

To resolve this issue, you must manually delete the conflicting queues from the RabbitMQ broker. Once the queues are
deleted,
you can restart the microservice to recreate them with the correct, updated properties.
