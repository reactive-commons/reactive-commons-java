---
sidebar_position: 1
---

# RabbitMQ Configuration

This page describes how to configure RabbitMQ connection and messaging properties for each **domain** in Reactive
Commons. A domain represents an independent connection to a RabbitMQ broker. Your application can work with a single
domain (one broker) or multiple domains (several independent brokers), each with its own properties.
See [Communication Scenarios](/reactive-commons-java/docs/category/communication-scenarios) for guidance on when to use
multiple domains.

All available properties are defined in the
[AsyncProps](https://github.com/reactive-commons/reactive-commons-java/blob/master/starters/async-rabbit-starter/src/main/java/org/reactivecommons/async/rabbit/config/props/AsyncProps.java)
class. There are two ways to provide these values via `application.yaml` or a combination of YAML and programmatic
configuration, as described in the [Configuration approaches](#configuration-approaches) section below.

```yaml title="application.yaml"
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
        maxConcurrency: 250 # max concurrency of listener flow. The handlers of all the listeners run on one pool shared by the whole application, that grows up to 250 threads only when the handlers block (blocking I/O) and releases the idle threads after a minute
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
        ssl:
          enabled: false # set to true to enable SSL/TLS for this domain's connection
    # Another domain can be configured with same properties structure that app
    accounts: # this is a second domain name and can have another independent setup
      connectionProperties: # you can override the connection properties of each domain
        host: localhost
        port: 5672
        username: guest
        password: guest
        virtual-host: /accounts
```

:::caution[Mandatory `app` Domain Configuration]
To ensure a correct configuration, you should always override the properties of the `app` domain. If it is not
configured, an exception will be thrown. You can also add properties for additional custom domain if needed.
:::

:::caution[Infinite retries and quorum queues]
With `withDLQRetry: false` and `maxRetries: -1` a failed message is requeued every `retryDelay` indefinitely. On
quorum queues, RabbitMQ 4.0 to 4.2 count those requeues against the queue
[delivery limit](https://www.rabbitmq.com/docs/quorum-queues#poison-message-handling) (20 by default): once reached,
the broker drops the message (or dead-letters it if the queue has a dead letter exchange). Use `withDLQRetry: true`
or a finite `maxRetries` if messages must not be dropped.
:::

## Configuration approaches

There are two ways to supply domain properties. Choose the one that best fits your use case.

### Approach 1: YAML only

Define all domains directly in `application.yaml` as shown above. This is the simplest approach and works well when
properties do not depend on runtime values such as secrets.

### Approach 2: Hybrid YAML + `RabbitPropsCustomizer`

Use this approach when you want to define the domain structure in YAML (topology, retry settings, etc.) but need to set
some properties at runtime for example, loading connection credentials from a secrets manager.

Declare your domains in `application.yaml` as usual, then define a `RabbitPropsCustomizer` bean to override specific
properties after the YAML is loaded. The customizer receives the full map of configured domains and can modify any
property on any domain.

:::caution[YAML domains are optional]
The `RabbitPropsCustomizer` can work with or without pre-existing YAML domains. If no domains are defined in your
`application.yaml` under `app.async`, you can define all domains directly inside the customizer using
`domainProperties.put("<domain>", AsyncProps.builder()...build())`. At least one domain must exist after the customizer
executes, otherwise an `InvalidConfigurationException` is thrown.
:::

You have two options:

**Option A: Define domains in YAML, then merge overrides with the customizer**

Declare your domains in `application.yaml` as usual, then use the customizer to override or extend them.

```yaml title="application.yaml"
app:
  async:
    app: # first domain
      withDLQRetry: true
      maxRetries: 3
      listenReplies: true
    accounts: # second domain
      listenReplies: false
```

```java
package sample;

import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

  @Bean
  public AsyncPropsDomain.RabbitPropsCustomizer rabbitPropsCustomizer() {
    return domainProperties -> {
      // Customize the "app" domain — YAML values are kept, only these fields are overridden
      domainProperties.customize("app", app -> app.setQueueType("quorum"));

      // Customize the "accounts" domain independently
      domainProperties.customize("accounts", accounts -> accounts.setQueueType("quorum"));
    };
    }

}
```

:::danger[`put` replaces the domain, `customize` merges it]
`domainProperties` is a map of domains, so `domainProperties.put("app", AsyncProps.builder()...build())`
**replaces the whole domain** and every value bound from `application.yaml` for that domain is lost, going back to its
defaults. Use `customize(domain, props -> ...)` to merge, and `put(domain, props)` only when you intend to define the
whole domain programmatically.
:::

**Option B: Define all domains in the customizer (no YAML domains)**

If you prefer full programmatic control, **omit the `app.async` section entirely from your `application.yaml`** and
define all domains inside the customizer:

```java
package sample;

import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    private RabbitProperties loadFromSecret(String secretName) {
        // ...
        return new RabbitProperties();
    }

    @Bean
    public AsyncPropsDomain.RabbitPropsCustomizer rabbitPropsCustomizer() {
        return domainProperties -> {
            // Define all domains programmatically
            domainProperties.put("app", AsyncProps.builder()
                    .withDLQRetry(Boolean.TRUE)
                    .maxRetries(3)
                    .listenReplies(Boolean.TRUE)
                    .connectionProperties(loadFromSecret("secret-app-rabbit"))
                    .build());

            domainProperties.put("accounts", AsyncProps.builder()
                    .listenReplies(Boolean.FALSE)
                    .connectionProperties(loadFromSecret("secret-accounts-rabbit"))
                    .build());
        };
    }
}
```

**Key rules for the hybrid approach:**

- Properties set in the customizer **take precedence** over YAML values.
- YAML values not touched by the customizer are **preserved**, as long as you use
  `domainProperties.customize("domain", props -> ...)` or mutate the instance returned by
  `domainProperties.get("domain")`.
- `domainProperties.put("domain", props)` **replaces** the whole domain: use it to define domains programmatically, not
  to override a few properties of a domain declared in YAML.
- The customizer can also **add new domains** by calling `domainProperties.put("newDomain", asyncProps)`.

## Loading properties from a secret

:::danger[Deprecated]
Using `AsyncPropsDomain.RabbitSecretFiller` to load secrets is **deprecated** and will be removed in a future version.
Use **[Approach 2: Hybrid YAML + `RabbitPropsCustomizer`](#approach-2-hybrid-yaml--rabbitpropscustomizer)** instead,
which provides full control over all domain properties at runtime and is the recommended way to integrate with a secrets
manager.
:::

The recommended way to load connection properties from a secrets manager is to use the `RabbitPropsCustomizer` (see
[Approach 2](#approach-2-hybrid-yaml--rabbitpropscustomizer)). This gives you full control over all domain properties at
runtime. The example below uses the [Secrets Manager](https://github.com/bancolombia/secrets-manager) library.

1. Create a `@ConfigurationProperties` record to map the secret fields:

```java
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "helpers.secrets-manager")
public record SecretsManagerProperties(
        String endpoint,
        Integer cacheSize,
        Integer cacheTime,
        String rabbit) {
}
```

2. Create a `RabbitMQConnectionProperties` record to map the fields of your secret and provide a conversion method:

```java
import org.reactivecommons.async.rabbit.config.RabbitProperties;

public record RabbitMQConnectionProperties(
        String virtualhost,
        String host,
        String password,
        String username,
        boolean ssl,
        Integer port) {
}
```

3. Create a `SecretsConfig` class that registers the `GenericManager` bean and exposes the RabbitMQ secret as a bean:

```java
import co.com.bancolombia.secretsmanager.api.GenericManager;
import co.com.bancolombia.secretsmanager.connector.AWSSecretManagerConnector;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;

@Log4j2
@Configuration
@RequiredArgsConstructor
public class SecretsConfig {

    private final SecretsManagerProperties properties;
    private static final String REGION_SECRET = Region.US_EAST_1.toString();

    @Bean
    @Profile("!local")
    public GenericManager connectionAws() {
        return new AWSSecretManagerConnector(REGION_SECRET);
    }

    @Bean
    @Profile("local")
    public GenericManager connectionLocal() {
        return new AWSSecretManagerConnector(REGION_SECRET, properties.endpoint());
    }

    public <T> T getSecret(String secretName, Class<T> cls, GenericManager connector) {
        try {
            log.info("Secret was obtained successfully");
            return connector.getSecret(secretName, cls);
        } catch (Exception e) {
            log.error("Error getting secret: {}", e.getMessage());
            return null;
        }
    }

    @Bean
    public RabbitMQConnectionProperties getSecretRabbitmq(GenericManager connector) {
        return this.getSecret(properties.rabbit(), RabbitMQConnectionProperties.class, connector);
    }
}
```

4. Create a separate `RabbitMQConfig` class that injects the `RabbitMQConnectionProperties` bean and defines the
   `RabbitPropsCustomizer`:

```java
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final RabbitMQConnectionProperties rabbitMQConnectionProperties;

    @Bean
    public AsyncPropsDomain.RabbitPropsCustomizer rabbitPropsCustomizer() {
        return domainProperties -> {
          domainProperties.customize("app", properties -> {
            RabbitProperties connectionProperties = properties.getConnectionProperties();
            connectionProperties.setHost(rabbitMQConnectionProperties.host());
            connectionProperties.setPort(rabbitMQConnectionProperties.port());
            connectionProperties.setVirtualHost(rabbitMQConnectionProperties.virtualhost());
            connectionProperties.setUsername(rabbitMQConnectionProperties.username());
            connectionProperties.setPassword(rabbitMQConnectionProperties.password());
            connectionProperties.getSsl().setEnabled(rabbitMQConnectionProperties.ssl());
          });
        };
    }
}
```

## Topology creation and permissions

The user of the connection must have the permissions required by the features that you enable. RabbitMQ permissions are
granted per virtual host through three regular expressions, **configure**, **write** and **read**:

| Operation                                                  | Permission required                                                                                                                                                       |
|------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `createTopology: true` (declare exchanges and queues)      | **configure** on the exchanges and queues to declare                                                                                                                      |
| `createTopology: true` (bind a queue to an exchange)       | **write** on the queue and **read** on the exchange                                                                                                                       |
| Send events, commands and queries                          | **write** on the target exchange                                                                                                                                          |
| Listen events, commands and queries                        | **read** on the queue                                                                                                                                                     |
| `listenReplies: true` (exclusive, auto-delete reply queue) | **configure**, **write** and **read** on the reply queue, and **read** on the replies exchange. The queue is always declared and bound, even with `createTopology: false` |

Without these permissions the broker closes the channel with an `ACCESS_REFUSED` error. Declaring a resource that
already exists does not require changing it, but the user still needs **configure** permission on it.

:::tip[Restricted environments]
When your organization does not allow the application user to create resources, set `createTopology: false` and create
the exchanges, queues and bindings with your infrastructure process, including the DLQ and retry ones if you use
`withDLQRetry`. The user then only needs **write** on the exchanges it sends to and **read** on the queues it
listens to, plus the permissions of the reply queue above if you use `listenReplies: true`.
:::

## Customizing the connection

For advanced control over the RabbitMQ connection, you can define a `ConnectionFactoryCustomizer` bean. This allows you
to configure options that are not exposed through standard properties, such as custom timeouts, SSL/TLS settings, or
automatic recovery strategies:

```java

@Bean
public ConnectionFactoryCustomizer connectionFactoryCustomizer() {
    return (connectionFactory, asyncProps) -> {
        connectionFactory.setExceptionHandler(new MyCustomExceptionHandler()); // Optional custom exception handler
        connectionFactory.setCredentialsProvider(new MyCustomCredentialsProvider()); // Optional custom credentials provider
        return connectionFactory;
    };
}
```

## Connections and channels

Reactive Commons establishes **up to two connections to the RabbitMQ broker per domain**: one to publish
(`<appName>-<host>-sender`) and, as soon as the application listens (listeners or `listenReplies`), one to consume
(`<appName>-<host>-listener`). During a [resource alarm](https://www.rabbitmq.com/docs/alarms) the broker stops reading
from the connections that publish; keeping the consumers on their own connection lets them keep acknowledging messages,
which is what drains the queues and frees the broker. The number of open **channels** varies depending on the enabled
annotations and the type of interaction (sending, listening, or both); the tables report the channels of both
connections of a domain. Each scenario described below shows how they change according to the applied configuration.

In the context of this documentation, a domain refers to a connection with a broker. The configuration supports up to
two brokers, which means the described scenarios are limited to a maximum of two domains.

### Annotations used in the tables

**[1] Annotations for sending messages:**

- `@EnableDomainEventBus` to
  send [domain events](/reactive-commons-java/docs/reactive-commons/sending-a-domain-event/rabbitmq).
- `@EnableDirectAsyncGateway` to send [commands](/reactive-commons-java/docs/reactive-commons/sending-a-command)
  and [asynchronous queries](/reactive-commons-java/docs/reactive-commons/making-an-async-query).

**[2] Annotations for listening to messages:**

- `@EnableEventListeners` to
  listen [domain events](/reactive-commons-java/docs/reactive-commons/handling-domain-events/rabbitmq).
- `@EnableCommandListeners` to listen [commands](/reactive-commons-java/docs/reactive-commons/handling-commands).
- `@EnableQueryListeners` to serve [async queries](/reactive-commons-java/docs/reactive-commons/serving-async-queries).

### 1. Sending messages (single domain)

> In this scenario we only use annotations to enable message sending only, along with different configurations for the
> `listenReplies` property:

| Enabled annotations        | listenReplies | Broker     | Connections | Channels |
|----------------------------|---------------|------------|-------------|----------|
| One or all for sending [1] | true          | Broker app | 2           | 8        |
|                            | false         | Broker app | 1           | 6        |

### 2. Sending messages (multiple domains)

> In this scenario, we only send messages to two brokers, using one or all of the annotations and configurations for the
> `listenReplies` property:

| Enabled annotations        | listenReplies | Broker            | Connections | Channels |
|----------------------------|---------------|-------------------|-------------|----------|
| One or all for sending [1] | true          | Broker app        | 2           | 8        |
|                            |               | Additional broker | 2           | 8        |
| One or all for sending [1] | false         | Broker app        | 1           | 6        |
|                            |               | Additional broker | 1           | 6        |

### 3. Listening for messages (single domain)

> This scenario enables only listening for messages from a single broker, using one or all available annotations:

| Enabled annotations          | Broker     | Connections | Channels |
|------------------------------|------------|-------------|----------|
| One or all for listening [2] | Broker app | 2           | 9        |

### 4. Listening for messages (multiple domains)

> In this scenario, messages are listened to from two brokers, with variations in the annotations enabled:

| Enabled annotations   | Broker            | Connections | Channels |
|-----------------------|-------------------|-------------|----------|
| All for listening [2] | Broker app        | 2           | 9        |
|                       | Additional broker | 2           | 8        |
| Two for listening [2] | Broker app        | 2           | 8        |
|                       | Additional broker | 2           | 8        |
| One for listening [2] | Broker app        | 2           | 7        |
|                       | Additional broker | 2           | 7        |

### 5. Sending and listening for messages (single domain)

> This scenario enables both sending and listening for messages on a single broker, with all annotations enabled:

| Enabled annotations                           | listenReplies | Broker     | Connections | Channels |
|-----------------------------------------------|---------------|------------|-------------|----------|
| All for sending [1] and all for listening [2] | true          | Broker app | 2           | 11       |
|                                               | false         | Broker app | 2           | 10       |

### 6. Sending and listening for messages (multiple domains)

> In this scenario, messages are sent and listened from two brokers, with variations in the annotations enabled:

| Enabled annotations                           | Broker            | Connections | Channels |
|-----------------------------------------------|-------------------|-------------|----------|
| All for sending [1] and all for listening [2] | Broker app        | 2           | 10       |
|                                               | Additional broker | 2           | 9        |
| One for sending [1] and all for listening [2] | Broker app        | 2           | 10       |
|                                               | Additional broker | 2           | 9        |
| All for sending [1] and two for listening [2] | Broker app        | 2           | 9        |
|                                               | Additional broker | 2           | 9        |
| All for sending [1] and one for listening [2] | Broker app        | 2           | 8        |
|                                               | Additional broker | 2           | 8        |
| All for sending [1]                           | Broker app        | 1           | 6        |
|                                               | Additional broker | 1           | 6        |

### How the channels are used

- **The number of channels does not grow with the traffic.** Each domain opens a fixed set of channels when it
  starts: the sender uses 5 (4 with publisher confirms and 1 without them) and each listener uses its own. More
  messages per second travel through the same channels.
- **Discarded messages share the sender of the default domain.** The notification of a discarded message (the
  `.dlq` events) is published through the sender of the `app` domain, so it does not open channels of its own, no
  matter how many domains are configured. Only a domain that sets `useDiscardNotifierPerDomain` publishes the
  notifications of its own discards through its own sender.

### Recommendations

- **Resource Optimization:** If only sending commands and events is required, disabling the `listenReplies` property
  reduces the number of open channels.
- **Selective Annotation Activation:** Enabling only the necessary annotations for the use case can improve performance
  and simplify configuration.
- **Proper Use of Configuration Properties:** Adjusting configuration properties according to the specific use case
  allows for resource optimization and avoids unnecessary configurations.

## Sender resilience and back-pressure

Every publish terminates within a bounded time with a typed outcome, so a hung broker (or socket) cannot silently
stall the publisher. While the broker signals `connection.blocked` (memory or disk alarm), new publishes fail
immediately with `SendFailureBrokerBlockedException` instead of waiting for `sendTimeout`: the message is definitively
not published, so the caller can retry it safely once the alarm clears. This is not configurable. The relevant
properties and their defaults:

| Property | Default | Purpose |
|---|---|---|
| `sendTimeout` | `10s` | Maximum time a sender waits for the outcome of a message before failing the caller. |
| `handOverPoolSize` | `13` | Size of the publish hand-over thread pool (threads that call `basicPublish` and can block on broker back-pressure). |
| `handOverQueueCapacity` | `1024` | Maximum number of messages waiting to be written to a channel (queued for the hand-over pool, or handed to a publishing flow whose channel is busy, e.g. while the broker blocks the connection). When reached, new publishes fail fast instead of growing in memory. |
| `blockedHealthGrace` | `5s` | How long a `connection.blocked` alarm must persist before the health indicator reports DOWN. |

```yaml title="application.yaml"
app:
  async:
    app:
      sendTimeout: 10s
      handOverPoolSize: 13
      handOverQueueCapacity: 1024
      blockedHealthGrace: 5s
```

### Publisher outcome exceptions

Every send terminates with one of the following typed exceptions. They all extend `SendFailureNoAckException`, so
existing catch-blocks keep working.

| Exception | When it fires | Caller contract |
|---|---|---|
| `SendFailureNoAckException` | No publishing flow became available within `sendTimeout`, the message was still waiting to be written when `sendTimeout` elapsed (it is then dropped, never published late), or the broker returned a channel-level nack. | Message was NOT published. Safe to retry. |
| `SendConfirmUnknownException` | Message was handed to a flow but the broker did not confirm it in time (channel/connection failed, or `sendTimeout` elapsed with the message already in flight). | **At-least-once:** the message may or may not have reached the broker; a retry can produce a duplicate. Make the consumer idempotent, or use a correlation ID to deduplicate. |
| `SendFailureReturnedException` | Mandatory publish with no queue bound to the routing key. | Message was NOT delivered. Safe to retry after fixing the topology. |
| `SendFailureBrokerBlockedException` | The broker signalled `connection.blocked` (memory or disk alarm) and has not unblocked the connection yet: the publish fails immediately instead of waiting for `sendTimeout`. | Message was NOT published. Safe to retry after the alarm clears. |
| `SendFailureHandOverRejectedException` | The hand-over queue was full (sustained back-pressure, `handOverQueueCapacity` reached). | Message was NOT published. Safe to retry; a flood of these means the broker is not draining faster than the application produces. |

:::caution[`requestReply` is at-least-once]
The query request and its reply are both published with `sendNoConfirm`. On a `SendFailureNoAckException` after
`sendTimeout`, the request **may still have reached the broker** — callers must either be idempotent or use a
correlation ID to deduplicate replies. The same caveat applies to the `reply(...)` call in query handlers.
:::

### Observability

The sender exposes the following metrics on the application's `MeterRegistry`:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `reactive_commons.publish.outcome` | Counter | `outcome`, `confirm` ∈ {`true`, `false`}, `domain` | One per published message, tagged with its final outcome. With `confirm=true` (events, commands): `ack`, `nack`, `unknown`, `returned`, `rejected`, `timeout`, `blocked`. With `confirm=false` (queries and replies): `published` (written to the channel, **not** confirmed by the broker), `failed`, `rejected`, `timeout`, `blocked`. |
| `reactive_commons.publish.flow.resubscribe` | Counter | `kind` ∈ {`confirm`, `noConfirm`}, `domain` | A publishing flow terminated and is being re-subscribed. |
| `reactive_commons.publish.flow.live` | Gauge | `kind`, `domain` | Number of publishing flows currently holding a channel. |
| `reactive_commons.publish.connection.blocked` | FunctionCounter | `domain` | Number of `connection.blocked` alarms observed since the application started. |
| `reactive_commons.publish.connection.blocked.state` | Gauge | `domain` | `1` while the broker is blocking the connection, `0` otherwise. |
| `reactive_commons.publish.hand_over.queue.size` | Gauge | `domain` | Current depth of the publish hand-over queue — a sustained non-zero value signals back-pressure. |

## Subscriber resilience and observability

Every listener (events, commands, queries, queue listeners, notifications, replies) is tracked individually: a
listener that silently stops delivering (orphaned consumer tag, failing restart loop, lost reply queue, hung
handler) surfaces as DOWN in the health check with the broken queue in the details, and as counters/gauges on the
`MeterRegistry`.

| Property | Default | Purpose |
|---|---|---|
| `subscriberInactiveGrace` | `10s` | How long a listener may stay inactive (channel closed, consumer cancelled, upstream error) before the health indicator reports DOWN. A short gap during normal re-subscription should not flap the check. |
| `handlerTimeout` | *(off)* | Maximum time a handler may run before its message is nacked as a failed delivery. When set, a hung handler nacks instead of holding a prefetch slot forever, and its thread is interrupted (code that ignores interrupts, such as a socket read without timeout, keeps it). The message is redelivered while the timed-out handler may still have produced side effects: handlers must be idempotent. Combine with `maxRetries` or `withDLQRetry` to avoid a hot-requeue loop on a chronically slow handler. |

```yaml title="application.yaml"
app:
  async:
    app:
      subscriberInactiveGrace: 10s
      handlerTimeout: 30s
```

### Listener restart

When the subscription terminates (channel closed, consumer cancelled, upstream error), the listener re-subscribes
with the same **200 ms → 5 s exponential backoff** used by the sender's publisher-confirms flows. The first restart
per listener is logged at WARNING so an outage is visible; subsequent attempts log at FINE to avoid flooding when
the broker is continuously unavailable. A listener is active once the broker has registered its consumer
(`basic.consume-ok`), not when its channel opens. The failure counter is cleared when a message is delivered, or
once the consumer has stayed registered for 5 seconds, so a recovered listener on a queue without traffic is healthy
again, while one that keeps failing (e.g. a `PRECONDITION_FAILED` loop on a diverged queue) or that the broker keeps
dropping right after registering it stays reported as broken.

### Request/reply replies are at-least-once

`requestReply` publishes the request with `sendNoConfirm` and expects the reply through an **exclusive + autoDelete**
reply queue. The broker deletes that queue as soon as its connection is lost, and any reply routed to it until the
connection is recovered is dropped. When the reply connection is lost, or the reply listener's subscription
terminates, the pending `requestReply` calls **of that domain** fail immediately with a
`ReplyLostOnReconnectException` instead of waiting for the reply timeout; the queries of the other domains, whose
reply queues are fine, are not affected. A query sent while the reply connection is still down can lose its reply
the same way and then fails with the reply timeout. Design callers to be idempotent, or deduplicate replies with the
correlation ID.

### Subscriber metrics

The listener side exposes the following metrics on the application's `MeterRegistry`:

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `reactive_commons.consumer.messages` | Counter | `outcome` ∈ {`ack`, `nack`, `discard`, `dlq`, `handler_error`}, `queue`, `domain` | One per message processed by a listener, tagged with its outcome. |
| `reactive_commons.consumer.handler.latency` | Timer | `queue`, `domain` | Time taken by the handler, from the moment the message was delivered to the moment it completed (ack) or failed. |
| `reactive_commons.consumer.restart` | Counter | `reason` ∈ {`channel_shutdown` (the broker closed the channel), `cancel` (the broker cancelled the consumer, e.g. its queue was deleted), `error` (the subscription failed, e.g. the queue could not be declared)}, `queue`, `domain` | One per listener restart, with the reason that triggered it. A sustained non-zero rate on a specific queue signals an orphaned consumer or diverged topology. |
| `reactive_commons.consumer.active` | Gauge | `queue`, `domain` | `1` while the broker has a consumer registered for the listener, `0` otherwise. |
| `reactive_commons.consumer.last_message_age_seconds` | Gauge | `queue`, `domain` | Seconds since the last message was delivered to the listener. A large value on a queue that should be receiving traffic is a reliable "stuck consumer" signal. |
| `reactive_commons.consumer.replies.lost` | Counter | `domain` | Number of pending `requestReply` correlations that were failed with `ReplyLostOnReconnectException` after a reply-listener restart. |

### Connections in microservices with multiple replicas

In a typical cloud production environment, such as AWS, microservices are deployed in containers orchestrated by
Kubernetes, using managed services like Amazon EKS. For the messaging broker, Amazon MQ for RabbitMQ is used, configured
in a 3-node cluster with a Multi-AZ deployment to ensure high availability and fault tolerance.

When working with microservices that use multiple replicas (instances) and implement Reactive Commons, it is important
to understand how connections to the message broker are managed. Each replica of a microservice establishes, per domain,
**one connection to send** messages and, if it listens, **one connection to listen** to them.

The number of open channels within those connections will depend on the configuration of the annotations used, as
described in the connection scenarios above. This allows each replica to manage its messaging operations independently,
distributing the workload efficiently.

For example, if a microservice that sends and listens on one domain is deployed with **4 replicas**, each of them will
establish its own two connections to the broker. As a result, the entire microservice deployment will have a total of
**8 connections** to the broker (4 if it only sends with `listenReplies: false`).

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
are displayed in a log. To customize this behavior, a class that implements the `UnroutableMessageHandler` interface is
created and registered as a Spring bean:

```java
package sample;

import co.com.mypackage.usecase.MyUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;

import java.nio.charset.StandardCharsets;

@Log
@Component
@RequiredArgsConstructor
public class ResendUnroutableMessageHandler implements UnroutableMessageHandler {

    private final MyUseCase useCase;

    @Override
    public Mono<Void> processMessage(OutboundMessageResult<OutboundMessage> result) {
        var returned = result.outboundMessage();
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
[domain events](/reactive-commons-java/docs/reactive-commons/sending-a-domain-event/rabbitmq), and
`@EnableDirectAsyncGateway`
for [commands](/reactive-commons-java/docs/reactive-commons/sending-a-command) and
[asynchronous queries](/reactive-commons-java/docs/reactive-commons/making-an-async-query), as appropriate.

It is important to ensure that the queue exists before sending the message, as it will otherwise be lost. Therefore, it
is recommended to verify or create the queue beforehand to ensure successful delivery.

```java
package sample;

import lombok.extern.java.Log;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Log
@Component
@EnableDirectAsyncGateway
public class ResendUnroutableMessageHandler implements UnroutableMessageHandler {

    private final JsonMapper jsonMapper;
    private final String retryQueueName;
    private final DirectAsyncGateway gateway;

    public ResendUnroutableMessageHandler(
            JsonMapper jsonMapper,
            DirectAsyncGateway gateway,
            @Value("${adapters.rabbitmq.retry-queue-name}") String retryQueueName) {
        this.jsonMapper = jsonMapper;
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
    public Mono<Void> processMessage(OutboundMessageResult<OutboundMessage> result) {
        OutboundMessage returned = result.outboundMessage();
        try {
            // The unroutable message is a command, so the message body is deserialized to the Command class.
            // Use the DomainEvent class for domain events and the AsyncQuery class for asynchronous queries.
            Command<JsonNode> command = jsonMapper.readValue(returned.getBody(), new TypeReference<>() {
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
of the queue that already exists in the RabbitMQ broker. It commonly happens when you try to:

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
`x-dead-letter-exchange` argument. When your application starts, it tries to declare the queue with the new properties,
but the broker rejects the declaration because it conflicts with the existing queue.

**Solution:**

To resolve this issue, you must manually delete the conflicting queues from the RabbitMQ broker. Once the queues are
deleted, you can restart the microservice to recreate them with the correct, updated properties.

