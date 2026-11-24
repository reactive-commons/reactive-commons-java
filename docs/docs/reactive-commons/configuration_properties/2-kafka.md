---
sidebar_position: 2
---

# Kafka Configuration

This page describes how to configure Kafka connection and messaging properties for each **domain** in Reactive Commons.
A domain represents an independent connection to a Kafka cluster. Your application can work with a single domain (one
cluster) or multiple domains (several independent clusters), each with its own properties.
See [Communication Scenarios](/reactive-commons-java/docs/category/communication-scenarios) for guidance on when to use
multiple domains.

All available properties are defined in the
[AsyncKafkaProps](https://github.com/reactive-commons/reactive-commons-java/blob/master/starters/async-kafka-starter/src/main/java/org/reactivecommons/async/kafka/config/props/AsyncKafkaProps.java)
class. There are two ways to provide these values via `application.yaml` or a combination of YAML and programmatic
configuration, as described in the [Configuration approaches](#configuration-approaches) section below.

```yaml title="application.yaml"
reactive:
  commons:
    kafka:
      app: # this is the name of the default domain
        withDLQRetry: false # if you want to have dlq topics with retries you can set it to true, you cannot change it after queues are created, because you will get an error, so you should delete topology before the change.
        maxRetries: -1 # -1 will be considered default value. When withDLQRetry is true, it will be retried 10 times. When withDLQRetry is false, it will be retried in place until it succeeds, see Retries of a failed message.
        retryDelay: 1000 # interval for message retries, with and without DLQRetry
        checkExistingTopics: true # if you don't want to verify topic existence before send a record you can set it to false
        sendTimeout: 10s # how long a send waits for the broker to acknowledge its record before failing, see Publishing
        maxPendingSends: 10000 # how many sends may wait for their outcome at once; beyond it a send fails at once, see Publishing
        healthTimeout: 3s # how long the health check waits for the cluster before reporting the domain DOWN, see Health check
        createTopology: true # if your organization have restrictions with automatic topology creation you can set it to false and create it manually or by your organization process.
        useDiscardNotifierPerDomain: false # if true it uses a discard notifier for each domain,when false it uses a single discard notifier for all domains with default 'app' domain
        enabled: true # if you want to disable this domain you can set it to false
        brokerType: "kafka" # please don't change this value
        domain:
          ignoreThisListener: false # Allows you to disable event listener for this specific domain
        connectionProperties: # you can override the connection properties of each domain
          bootstrap-servers: localhost:9092
          consumer:
            group-id: # optional. When set, it is used as the consumer group id of the domain events listener and of the topic listeners, exactly as provided
      # Another domain can be configured with same properties structure that app
      accounts: # this is a second domain name and can have another independent setup
        connectionProperties: # you can override the connection properties of each domain
          bootstrap-servers: localhost:9093
```

## Connection properties

`connectionProperties` of each domain is a
[KafkaProperties](https://github.com/reactive-commons/reactive-commons-java/blob/master/starters/async-kafka-starter/src/main/java/org/reactivecommons/async/kafka/config/spring/KafkaPropertiesBase.java)
instance, which has the same structure as the well known `spring.kafka.*` properties of Spring Boot. It can be defined
**in YAML, programmatically, or both** — YAML is bound first and a `KafkaPropsCustomizer` can override it afterwards
(see [Approach 2](#approach-2-hybrid-yaml--kafkapropscustomizer)).

The class exposes typed sections (`consumer`, `producer`, `admin`, `ssl`, `security`) plus free-form `properties`
maps for any Kafka client property that is not modeled as a field. Relaxed binding applies, so
`bootstrapServers`, `bootstrap-servers` and `BOOTSTRAP_SERVERS` are equivalent in YAML.

```yaml title="application.yaml"
reactive:
  commons:
    kafka:
      app:
        connectionProperties:
          bootstrap-servers: broker1:9094,broker2:9094 # list, can also be written as a YAML sequence
          consumer:
            group-id: dummy.consumer-group # consumer group used by the domain events listener
            auto-offset-reset: earliest
            max-poll-records: 250
          security:
            protocol: SASL_SSL
          ssl:
            trust-store-type: PEM
            trust-store-location: file:/etc/certs/kafka.pem
          properties: # raw Kafka client properties, common to consumer, producer and admin
            sasl.mechanism: SCRAM-SHA-512
            sasl.jaas.config: org.apache.kafka.common.security.scram.ScramLoginModule required username="user" password="pass";
```

Equivalent programmatic definition:

```java
var propertiesApp = new KafkaProperties();
propertiesApp.setBootstrapServers(List.of("broker1:9094", "broker2:9094"));
propertiesApp.getConsumer().setGroupId("dummy.consumer-group");
propertiesApp.getSecurity().setProtocol("SASL_SSL");
propertiesApp.getProperties().put("sasl.mechanism","SCRAM-SHA-512");
```

Everything under `connectionProperties` is translated into plain Kafka client properties when the consumer, producer and
admin clients are created (`buildConsumerProperties()`, `buildProducerProperties()`, `buildAdminProperties()`). Within
the consumer, precedence is: `connectionProperties.properties` (common), `connectionProperties.consumer.*`
typed fields, `connectionProperties.consumer.properties` (raw consumer-specific). The connection properties of a domain
are never changed by the library: each client builds its own map from them.

### Properties that have no effect

`KafkaProperties` is a copy of the `spring.kafka.*` properties of Spring Boot, and some of its sections configure
Spring for Apache Kafka components that Reactive Commons does not use. They are bound but have no effect, and the
application logs a `WARN` at startup for each one that is configured (by its name only, never its value):

| Property                              | Why it has no effect                                                                                                                                                                                                             |
|---------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `consumer.enable-auto-commit: true`   | The offsets are committed by the library once each message is handled; auto commit is always disabled.                                                                                                                           |
| `consumer.auto-commit-interval`       | Same reason. Use `commitInterval` in a [`KafkaReceiverOptionsCustomizer`](#customizing-the-reactor-kafka-options).                                                                                                               |
| `admin.fail-fast: true`               | It is read by the `KafkaAdmin` of Spring for Apache Kafka, which Reactive Commons does not register: the application starts even when the broker cannot be reached, see [Starting without a broker](#starting-without-a-broker). |
| `listener.*`, `template.*`, `retry.*` | They configure the listener containers, `KafkaTemplate` and retry topics of Spring for Apache Kafka.                                                                                                                             |
| `streams.*`                           | Kafka Streams is not used.                                                                                                                                                                                                       |
| `jaas.*`                              | See [Authentication](#authentication-sasl--jaas).                                                                                                                                                                                |

### Client ids

Each Kafka client of a domain gets a `client.id` of its own: `<base>-producer` for the producer,
`<base>-consumer-<n>` for each consumer (a consumer created again after a failure gets a new number) and `<base>-admin`
for the admin client. `<base>` is `connectionProperties.client-id` when it is set, or else `spring.application.name`,
followed by a short random suffix of each domain of each instance (for example `orders-3fa91c`), so two clients never
share their JMX beans and metrics, and the broker tells them apart in its logs and quotas.

:::caution[Quotas and ACLs by client id]
Before this version every client of a domain used `spring.application.name` as its `client.id`. Quotas or ACLs defined
on that exact value must be changed, for example to a prefix.
:::

## TLS with SSL bundles

`connectionProperties.ssl.bundle` takes the TLS material from a
[Spring Boot SSL bundle](https://docs.spring.io/spring-boot/reference/features/ssl.html) (`spring.ssl.bundle.*`), as
`spring.kafka.ssl.bundle` does in Spring Boot: the consumer, the producer and the admin client of the domain get an
`SslEngineFactory` built from the bundle. A bundle set in `consumer.ssl.bundle`, `producer.ssl.bundle` or
`admin.ssl.bundle` replaces it for that client. A bundle that does not exist makes the application fail to start.

```yaml title="application.yaml"
spring:
  ssl:
    bundle:
      pem:
        kafka-tls:
          truststore:
            certificate: file:/etc/certs/kafka-ca.pem
reactive:
  commons:
    kafka:
      app:
        connectionProperties:
          bootstrap-servers: broker1:9094
          security:
            protocol: SSL
          ssl:
            bundle: kafka-tls
```

A bundle cannot be combined with the `ssl.key-store-*` and `ssl.trust-store-*` properties of the same section.

## Logging

The library logs through SLF4J, with its loggers under `org.reactivecommons`. Declare their level explicitly in the
configuration of the application, with `INFO` in production:

```yaml
logging:
  level:
    root: INFO
    org.reactivecommons: INFO
```

Use `DEBUG` only to diagnose a problem, in development or for a limited time, because at that level the library
writes one line for each message it handles, which slows the application down under load (see
[Logging in the applications](./1-rabbitmq.md#logging-in-the-applications)), and the headers and the body of the
failed or unreadable messages, which may carry personal data or credentials (see
[Retries of a failed message](#retries-of-a-failed-message)).

## Customizing the reactor-kafka options

The settings of reactor-kafka that have no property, such as `commitInterval`, `commitBatchSize`, `maxDeferredCommits`,
`maxDelayRebalance` or `pollTimeout` of the consumers, and `maxInFlight` of the producer, are adjusted with two
optional beans, applied to every domain after the values of the library, in their `@Order`:

```java
@Bean
public KafkaReceiverOptionsCustomizer receiverOptionsCustomizer() {
    return (domain, options) -> options
            .commitInterval(Duration.ofSeconds(1))
            .maxDelayRebalance(Duration.ofSeconds(10)); // how long a rebalance waits for the messages being handled
}

@Bean
public KafkaSenderOptionsCustomizer senderOptionsCustomizer() {
    return (domain, options) -> options.maxInFlight(512);
}
```

Some values are set again after the customizers, with a `WARN`, because the delivery depends on them:

- Consumers: `enable.auto.commit` stays `false`: an offset is only committed once its message was handled.
- Producer: `stopOnError` stays `false`: with `true` a single failed record would fail every send waiting for its
  outcome and stop the producer.

Do not change either the subscription or the assignment (each listener sets its own), the key and value deserializers
or serializers (the library reads and writes `String` keys and `byte[]` values), nor the producer properties that the
delivery relies on, such as `acks` or `enable.idempotence`, unless you accept the loss of acknowledged messages. The
producer and consumer properties set by a customizer are checked as the Kafka client reads them: a value it rejects
fails the startup. The consumers of the [notifications](#notifications), which have no consumer group, only keep the
`pollTimeout`, the `closeTimeout` and the client metrics. The producer customizer also applies to the producer that
publishes the discarded messages of the domains that share the discard notifier of `app`.

## Health check

Each domain reports its health in the `reactiveCommons` health indicator of Spring Boot Actuator. The domain is `UP`
when its cluster answers and none of its listeners has been restarting for more than 30 seconds:

- The cluster is asked for its id with the admin client of the domain, waiting at most `healthTimeout` (3 seconds by
  default), so the check answers well before the 10 seconds after which Spring Boot reports a slow health indicator,
  and before the timeout of the probes that read it. When the cluster does not answer, the domain is `DOWN`, the
  failure is in the `error` detail, and a single `WARN` line is logged.
- A listener restarts when its consumer stops (see [When a consumer stops](#when-a-consumer-stops)). A listener that
  consumes again within 30 seconds keeps the domain `UP`; one that cannot get its consumer to work for longer (a
  broker that cannot be reached, a missing permission) makes it `DOWN`.

The details of a domain are its `domain`, the cluster id (`version`) and, for each listener, its `topics`, whether it
is `restarting`, for how long (`restartingSeconds`) and how many times it restarted (`restarts`). They never contain
the connection properties.

## Metrics

When the application has a Micrometer `MeterRegistry` (Spring Boot Actuator declares one; without it the starter
uses an in-memory one), each domain publishes there:

- The metrics of its Kafka clients, bound by reactor-kafka while each client is open: `kafka.consumer.*` (for example
  `kafka.consumer.fetch.manager.records.lag.max`, the lag of the consumer) and `kafka.producer.*` (for example
  `kafka.producer.record.error.total`), tagged with `domain`, the `client.id` and the id of the reactor-kafka client.
- The metrics of its listeners, tagged with `domain` and `topic`:

| Meter                                              | Type    | What it counts                                                                                       |
|----------------------------------------------------|---------|------------------------------------------------------------------------------------------------------|
| `reactive_commons.kafka.consumer.messages`         | Counter | Messages done with, tag `outcome`: `ack` (handled), `discard` (failed for good, logged and skipped) or `dlq` (failed for good, sent to its DLQ) |
| `reactive_commons.kafka.consumer.handler.failures` | Counter | Failed attempts of a handler, retried or not                                                         |
| `reactive_commons.kafka.consumer.retry.attempts`   | Counter | Retries of a failed handler                                                                          |
| `reactive_commons.kafka.consumer.restart`          | Counter | Times the listener of the topic stopped consuming and was subscribed again                          |
| `reactive_commons.kafka.consumer.handler.latency`  | Timer   | From the start of the handling of a message to its outcome, retries included                        |

The names follow those of the RabbitMQ listeners (`reactive_commons.consumer.*`) under `reactive_commons.kafka`, so
both brokers can be used in the same application. A meter is registered the first time it is used. Every meter of a
domain is removed from the registry once the domain closes. The metrics of each open Kafka client are refreshed by a
thread of Micrometer (`micrometer-kafka-metrics`). To record nothing, declare a `@Primary` `KafkaMetricsFactory` bean
that returns `KafkaDomainMetrics.NONE`.

## Authentication (SASL / JAAS)

To connect to a secured cluster, define the security protocol, the SASL mechanism and the JAAS login configuration
through `connectionProperties`. They are applied to the consumer, producer and admin clients of the domain.

| Property                                  | Kafka client property        | Description                                                           |
|-------------------------------------------|------------------------------|-----------------------------------------------------------------------|
| `security.protocol`                       | `security.protocol`          | `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT` or `SASL_SSL`                    |
| `properties."sasl.mechanism"`             | `sasl.mechanism`             | `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512`, `GSSAPI`, `OAUTHBEARER`... |
| `properties."sasl.jaas.config"`           | `sasl.jaas.config`           | JAAS login module and its options, written in a single line           |
| `properties."sasl.kerberos.service.name"` | `sasl.kerberos.service.name` | Only for `GSSAPI` (Kerberos)                                          |

```yaml title="application.yaml (SASL/SCRAM over TLS)"
reactive:
  commons:
    kafka:
      app:
        connectionProperties:
          bootstrap-servers: broker1:9094,broker2:9094
          security:
            protocol: SASL_SSL
          properties:
            sasl.mechanism: SCRAM-SHA-512
```

```yaml title="application.yaml (SASL/PLAIN)"
reactive:
  commons:
    kafka:
      app:
        connectionProperties:
          bootstrap-servers: broker1:9092
          security:
            protocol: SASL_PLAINTEXT
          properties:
            sasl.mechanism: PLAIN
```

Do not keep credentials in the YAML file in real environments. Set `sasl.jaas.config` at runtime from your secrets
manager, mutating the existing connection properties so the rest of the YAML values are preserved:

```java

@Bean
public AsyncKafkaPropsDomain.KafkaPropsCustomizer kafkaPropsCustomizer(KafkaConnectionProperties secret) {
    return domainProperties -> domainProperties.customize("app", app -> {
        KafkaProperties connectionProperties = app.getConnectionProperties();
        connectionProperties.getProperties().put("sasl.jaas.config",
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"%s\" password=\"%s\";"
                        .formatted(secret.username(), secret.password()));
    });
}
```

:::caution[`connectionProperties.jaas.*` is not applied]
`KafkaProperties` also has a `jaas` section (`enabled`, `login-module`, `control-flag`, `options`), copied from
`spring.kafka.jaas.*` of Spring Boot. In Spring Boot that section is consumed by an auto-configuration that registers a
`KafkaJaasLoginModuleInitializer` bean. Reactive Commons does not register it, so those values are bound but **have no
effect**. Use `sasl.jaas.config` as shown above, which is also the recommended way in the Kafka client, because it
allows a different configuration per domain, while a JVM-wide JAAS login module is shared by all of them.
:::

## Topology creation and permissions

The user of the connection must have the permissions required by the features that you enable. Kafka reports
a missing permission as an authorization error. The library never lists the topics of the cluster: it only describes
the topics it sends to.

| Setting                     | What Reactive Commons does                                                                                                     | Permissions required (ACLs)                                                                 |
|-----------------------------|--------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| `checkExistingTopics: true` | Describes a topic before sending its first record, to check that it exists. It never creates a topic.                          | `DESCRIBE` on the topics sent to                                                            |
| `createTopology: true`      | Creates the topics (and their `.dlq` topics when `withDLQRetry` is true) that the listeners need, when they do not exist.      | `CREATE` on the cluster, or on the topics to be created (admin permission), plus `DESCRIBE` |
| Send events                 | Produces records to the topics.                                                                                                | `WRITE` and `DESCRIBE` on the topics                                                        |
| Listen events               | Consumes records with the configured consumer group (see [Consumer group](#consumer-group-for-domain-events)).                 | `READ` on the topics and `READ` on the consumer group                                       |
| Listen raw topics           | Consumes records with the consumer group of the topic listeners (see [Consumer group](#consumer-group-for-domain-events)).     | `READ` on the topics and `READ` on the consumer group                                       |
| Listen notifications        | Reads every partition of the notification topics without a consumer group (see [Notifications](#notifications)).               | `READ` and `DESCRIBE` on the topics; no consumer group                                      |
| Health check                | Asks the cluster for its id (see [Health check](#health-check)).                                                               | None beyond being able to connect                                                           |

:::tip[Restricted environments]
When your organization does not allow the application user to create topics, set
`createTopology: false` and create the topics, including the `.dlq` ones if you use `withDLQRetry`, with your
infrastructure process before deploying. Keep `checkExistingTopics: true` if you want the application to fail fast when
a topic is missing.
:::

### Starting without a broker

Creating the clients of a domain does not connect to the cluster, so the application starts even when the broker
cannot be reached (as `spring.kafka.admin.fail-fast: false`, the default of Spring Boot, does). The listeners keep
trying to subscribe (see [When a consumer stops](#when-a-consumer-stops)), the sends fail until the broker answers,
and the [health check](#health-check) reports the domain `DOWN`.

`connectionProperties.admin.fail-fast: true` has no effect: the application starts anyway, and logs a `WARN` (see
[Properties that have no effect](#properties-that-have-no-effect)). To detect a broker that cannot be reached, use the
[health check](#health-check); to detect a topic that does not exist, keep `checkExistingTopics: true`.

## Consumer group for domain events

The consumer group of the domain events listener is taken from the Kafka connection properties, so you configure it
where the rest of the technical connection settings live:

```yaml title="application.yaml"
reactive:
  commons:
    kafka:
      app:
        connectionProperties:
          bootstrap-servers: broker1:9094
          consumer:
            group-id: dummy.consumer-group
```

or programmatically, through the customizer:

```java

@Bean
public AsyncKafkaPropsDomain.KafkaPropsCustomizer kafkaPropsCustomizer() {
    return domainProperties -> domainProperties.customize("app", app -> {
                app.getConnectionProperties().setBootstrapServers(List.of("localhost:9092"));
                app.getConnectionProperties().getConsumer().setGroupId("dummy.consumer-group");
            }
    );
}
```

Resolution order for the events consumer group id:

1. The `group.id` present in the consumer connection properties, no matter how it was provided:
   `connectionProperties.consumer.group-id`, `connectionProperties.consumer.properties."group.id"` or
   `connectionProperties.properties."group.id"`. It is used **exactly as configured**, without appending any suffix.
2. Otherwise `${spring.application.name}-events`, the default convention.

Each domain resolves its own group id, so you can authorize a different consumer group per Kafka cluster.

The raw topic listeners use the same configured `group.id`, or `${spring.application.name}` when none is configured.
So with a configured `group.id`, the domain events and the raw topics share that consumer group, each with its own
consumer (see [Consumers of a domain](#consumers-of-a-domain)). The notifications use no consumer group.

## Consumers of a domain

Each domain uses one Kafka consumer per type of listener: one for its domain events and one for all its raw topics
(`listenTopic`), whatever the number of events or topics, and one for its notifications. Each consumer is a member of
the consumer group, with its own connection and its own thread (`reactive-kafka-<group>-N`); a consumer per topic would
add a member to the group for every topic, and rebalance every topic when any of them restarts.

The handlers of all the listeners of a domain run on a pool of the domain (threads `kafka-listeners-<domain>-N`), which
creates threads as they are needed and releases them once idle, so the partitions of every listener are handled at the
same time even when their handlers block.

### When the listeners start and stop consuming

The listeners start consuming once the application context is refreshed, after every bean is created and initialized:
a handler never runs while the beans it uses are still being created, nor when the startup fails afterwards. A listener
registered later starts right away. If you create a `KafkaBrokerProvider` yourself, outside of the Spring
configuration, call its `start()` method after registering its listeners.

When the application context closes, each domain stops consuming new messages and waits, up to 10 seconds, for the
messages being handled: they finish and their offsets are committed, so they are not delivered again after the restart.
The messages received and not started yet are not committed: the next consumer of their partition gets them. A message
whose handler keeps failing, or that cannot be sent to its `.dlq` topic yet, is not retried anymore and stays
uncommitted. After the wait the consumers are closed, then the producer and the admin client; closing a consumer or the
producer takes 10 seconds at most, and the admin client 5 seconds, so an unreachable broker does not hold the shutdown
of the application.

### When a consumer stops

If the flow of a listener stops (the consumer cannot be created, a poll or a commit fails, the topics cannot be
created), a new consumer is subscribed after a wait that starts at 200 ms and doubles, with a random variation, up to
5 seconds. The wait starts at 200 ms again once the new consumer delivers a message. The first failure in a row is
logged at `WARN` with its cause, and the next ones at `DEBUG`.

A consumer property that the Kafka client rejects (for example `auto-offset-reset: lastest`, or a `max.poll.records`
that is not a number) makes the application fail to start with the `ConfigException` of the client, instead of
failing every subscription of the listener once it runs. The properties of the `properties` maps are text: the client
reads `max.poll.records: "250"` as the number 250.

## Notifications

Every instance of the application receives every notification. Each instance reads all the partitions of the
notification topics by itself, without a consumer group, so it creates no group in the broker, needs no `READ`
permission on a group, and commits nothing.

An instance reads the notifications published since it started: the partitions are read from their end when the
listener starts. While the instance runs, the position of each partition is kept in memory: when the consumer is
subscribed again after a failure, it goes on from there, so no notification published meanwhile is skipped. The
partitions of the topics are checked every 30 seconds: a partition added to a topic is read from its beginning, so the
notifications published there since it was added are not skipped either.

## Retries of a failed message

With the default settings (`maxRetries: -1` and `withDLQRetry: false`), a message whose handler fails is retried in
place, every `retryDelay` milliseconds, until the handler succeeds. Its offset is not committed in the meantime, so the
message is not lost if the application stops: it is delivered again when the consumer group resumes. The messages after
it in the same partition wait until it is handled (see [Ordering and concurrency](#ordering-and-concurrency)).

A message that can never be handled is not retried: when its payload cannot be converted to the type of the handler
or does not match its schema, it is logged and skipped (or sent to its `.dlq` topic when `withDLQRetry` is true). A
schema registry that cannot be reached, or that answers with a transient error (429, 502, 503, 504), does not make a
message invalid: the message is retried. Any other answer of the registry (e.g. 401, 404 or 500) rejects the message
like an invalid one (see [Kafka Schema Validation](./3-kafka-schema-validation.md#consuming)).

With `withDLQRetry: true`, a failed message is retried `maxRetries` times, every `retryDelay` milliseconds, and then
sent to its `.dlq` topic. Left to its default (-1), `maxRetries` is 10 in this case.

With `maxRetries` set to 0 or more and `withDLQRetry: false`, a failed message is retried that many times, every
`retryDelay` milliseconds, and then discarded: it is logged and its offset is committed, so the consumer group does
not wait for it when it rebalances.

The retries and the discard run on the threads of the listeners of the domain, never on the threads Reactor shares
with the rest of the application (`Schedulers.parallel()`).

A discarded message is logged in a single `WARN` line with its topic, partition, offset, key, size and the class of the
error. Its stack trace, its headers and its body may carry personal data or credentials, so they are only logged at
`DEBUG` (logger `org.reactivecommons.async.kafka.listeners.GenericMessageListener`). A message that cannot be read is
logged in a single `ERROR` line with its key, topic and size; its body only at `DEBUG` (logger
`org.reactivecommons.async.commons.DLQDiscardNotifier`).

The application fails to start when `maxRetries` is lower than -1 or `retryDelay` is negative.

A message is acknowledged once it is in its `.dlq` topic. While it cannot be sent there, it is kept and sent again,
every few seconds, so it is never lost. A domain event or a notification goes to `<name>.dlq`, and one that cannot be
read goes to `corruptData.dlq`; with `createTopology: true` both topics are created when the listener starts. The
messages of a raw topic go to `<topic>.dlq` (see [Kafka topics](../handling-queues-and-topics/kafka.md)).

### Headers of a message in a DLQ

A message sent to a `.dlq` topic carries these headers, so it can be diagnosed, or published again, without searching
the logs:

| Header                     | Value                                                                       |
|----------------------------|-----------------------------------------------------------------------------|
| `x-dlq-original-topic`     | Topic the message was read from                                             |
| `x-dlq-original-partition` | Partition of that topic                                                     |
| `x-dlq-original-offset`    | Offset of the message in that partition                                     |
| `x-dlq-exception-class`    | Fully qualified class of the failure that discarded the message             |
| `x-dlq-exception-message`  | Message of that failure, cut to 1000 characters; absent when it has none    |

They are written as UTF-8 text. The message of the failure may contain data of the message: protect the `.dlq` topics
as you protect the topics they come from.

### Records without value (tombstones)

In a compacted topic, a record without value (a tombstone) deletes its key. The domain event and notification
listeners skip it, acknowledging it, since it carries no event. A raw topic listener gives it to its handler, with a
`null` body (see [Kafka topics](../handling-queues-and-topics/kafka.md)).

## Ordering and concurrency

The messages of a partition are handled one at a time, in the order of the partition, and the partitions assigned to a
listener are handled at the same time. A message is acknowledged once its handler completes, so the offset of a
partition is only committed past messages that were already handled: a message still being handled when the
application stops is delivered again when the consumer group resumes.

To handle more messages at the same time, give the topic more partitions, or run more instances of the application in
the same consumer group. Messages that must be handled in order, such as the events of the same entity, must be
published with the same key, so they go to the same partition.

While a message is being handled, or retried, the next messages of its partition wait in memory. When about 256 of
them are waiting, the listener pauses that partition on the Kafka consumer, and resumes it once half of them were
handled. The other partitions keep being fetched and handled, so a slow or failing message only holds back the messages
of its own partition, and the memory used by each partition is bounded.

## Publishing

Each domain publishes with one Kafka producer, which receives the records in the order they are sent: two `emit`
subscribed one after the other reach their partition in that order (unless a schema validator is configured, see
[Kafka Schema Validation](./3-kafka-schema-validation.md#blocking-behaviour-and-cache-lifetime)). The `Mono` of an
`emit` completes once the broker acknowledged its record, and your code continues on a thread of Reactor's
`Schedulers.boundedElastic()`, never on the threads of the producer. Nothing blocks the thread that calls `emit`.

| Property          | Default | What it does                                                                                                                                                                                                   |
|-------------------|---------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `sendTimeout`     | `10s`   | An `emit` whose record is not acknowledged within this time fails with `java.util.concurrent.TimeoutException`. The record may still be written later, so a timed out `emit` may be a duplicate if you send it again. |
| `maxPendingSends` | `10000` | How many `emit` may wait for their outcome at once. Beyond it an `emit` fails at once with `SendQueueFullException`, instead of piling up in memory while the broker cannot be reached; you can send it again later. |

The producer blocks on a record whose topic has no metadata yet (an unreachable broker, a topic that does not exist)
up to its `max.block.ms`, and the next records wait for it. When you do not set `max.block.ms` in the producer
properties, it takes the value of `sendTimeout` instead of the minute of the Kafka client.

When the topic of an `emit` does not exist:

- With `checkExistingTopics: true` (default), the `emit` fails at once with `TopicNotFoundException`. A topic not known
  yet is described with the admin client without blocking the caller, once for all the `emit` waiting for it; a topic
  found missing is reported missing for 30 seconds without asking the cluster again, and found once it is created. A
  topic found is described again after 5 minutes, so a topic deleted meanwhile is reported missing.
- With `checkExistingTopics: false`, the record is handed to the producer: if the cluster creates topics automatically
  (`auto.create.topics.enable`), the topic is created; otherwise the `emit` fails after `max.block.ms`.

The producer is created on the first `emit`. If it cannot be created (for example the bootstrap servers cannot be
resolved at that moment) or fails for good (an authentication error), the `emit` waiting for it fail and the next one
creates a new producer. A producer property that the Kafka client rejects (for example a misspelled value of `acks`)
makes the application fail to start, as a consumer property does.

## Consumer-only services

The Kafka producer of a domain is created when the domain publishes for the first time (with the `DomainEventBus`,
or when a message that exhausted its retries is sent to its `.dlq` topic). A service that only listens never opens a
producer, so it needs no `WRITE` permission and opens no producer connection to the cluster. Nothing has to be configured
for this.

When the application context closes, each domain lets the messages being handled finish (see
[When the listeners start and stop consuming](#when-the-listeners-start-and-stop-consuming)), then closes its consumers,
its producer (once the records already handed to it are sent) and its admin client. A domain uses a single admin
client, for its topology, its sends and its health check; the `app` domain shares it with the discard notifier that
publishes the discards of the other domains.

## Using RabbitMQ and Kafka in the same application

Each broker starter requires its own `app` domain, and a domain name belongs to a single broker. When both starters are
present, disable `app` in one of them and register the handlers of that broker on a domain with its own name. For
example, with RabbitMQ as the default broker:

```yaml title="application.yaml"
app:
  async:
    app: # RabbitMQ keeps the default domain
      maxRetries: 3
reactive:
  commons:
    kafka:
      app:
        enabled: false # the default domain belongs to RabbitMQ
      accounts: # the Kafka domain, with its own name
        connectionProperties:
          bootstrap-servers: localhost:9092
          consumer:
            group-id: accounts.consumer-group
```

```java
@Bean
public HandlerRegistry handlerRegistry() {
    return HandlerRegistry.register()
            .listenEvent("orders.created", handler::onOrderCreated, Order.class)  // RabbitMQ, 'app' domain
            .listenTopic("accounts", "accounts.events", handler::onAccountEvent); // Kafka, 'accounts' domain
}
```

If a domain is enabled in both brokers, the application fails to start:

```
Domain 'app' is enabled for both 'rabbitmq' and 'kafka'. A domain belongs to a single broker: disable it in one of
them (app.async.app.enabled: false or reactive.commons.kafka.app.enabled: false).
```

Setting `enabled: false` in YAML is enough. Declaring the `app` domain again in a `KafkaPropsCustomizer` with
`domainProperties.put("app", ...)` is only needed when `app` is not in your YAML, and remember that `put` replaces
the whole domain (see [Approach 2](#approach-2-hybrid-yaml--kafkapropscustomizer)): to change only some values of a
domain declared in YAML, use `domainProperties.customize(...)`.

## Schema validation

Payloads can be validated against a JSON Schema registered in an Apicurio Registry, both when publishing and when
consuming. See [Kafka Schema Validation (Apicurio)](./3-kafka-schema-validation.md).

## Configuration approaches

There are two ways to supply domain properties. Choose the one that best fits your use case.

### Approach 1: YAML only

Define all domains directly in `application.yaml` as shown above. No additional Java configuration is needed. This is
the simplest approach and works well when properties do not depend on runtime values such as secrets.

### Approach 2: Hybrid YAML + `KafkaPropsCustomizer`

Use this approach when you want to define the domain structure in YAML (topology, retry settings, etc.) but need to set
some properties at runtime — for example, loading bootstrap servers or credentials from a secrets manager.

Declare your domains in `application.yaml` as usual, then define a `KafkaPropsCustomizer` bean to override specific
properties after the YAML is loaded. The customizer receives the full map of configured domains and can modify any
property on any domain.

:::caution[YAML domains are optional]
The `KafkaPropsCustomizer` can work with or without pre-existing YAML domains. If no domains are defined in your
`application.yaml` under `reactive.commons.kafka`, you can define all domains directly inside the customizer using
`domainProperties.put("<domain>", AsyncKafkaProps.builder()...build())`. At least one domain must exist after the
customizer executes, otherwise an `InvalidConfigurationException` is thrown.
:::

You have two options:

**Option A: Define domains in YAML, then merge overrides with the customizer**

Declare your domains in `application.yaml` as usual, then use the customizer to override or extend them.

```yaml title="application.yaml"
reactive:
  commons:
    kafka:
      app: # first domain (will be treated as the default)
        retryDelay: 60000
        maxRetries: 3
      accounts: # second domain with independent cluster
        retryDelay: 40000
```

```java
package sample;

import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaConfig {

    // Loads Kafka connection properties from a secrets manager at runtime.
    // See the "Loading properties from a secret" section below for a complete implementation example.
    private KafkaProperties loadFromSecret(String secretName) {
        // ...
        return new KafkaProperties();
    }

    @Bean
    public AsyncKafkaPropsDomain.KafkaPropsCustomizer kafkaPropsCustomizer() {
        return domainProperties -> {
            // Customize the "app" domain — YAML values are kept, only these fields are overridden
            domainProperties.customize("app", app ->
                    app.setConnectionProperties(loadFromSecret("secret-app-kafka"))
            );

            // Customize the "accounts" domain independently
            domainProperties.customize("accounts", accounts ->
                    accounts.setConnectionProperties(loadFromSecret("secret-accounts-kafka"))
            );
        };
    }
}
```

:::danger[`put` replaces the domain, `customize` merges it]
`domainProperties` is a map of domains, so `domainProperties.put("app", AsyncKafkaProps.builder()...build())`
**replaces the whole domain** and every value bound from `application.yaml` for that domain is lost, including
`connectionProperties`, which goes back to its defaults (`localhost:9092`, no `group.id`, hence a consumer group
resolved as `${spring.application.name}-events`).

The same happens with `app.setConnectionProperties(newKafkaProperties)`: it replaces the whole connection properties
object, so anything configured in YAML under `connectionProperties` (for example `consumer.group-id`) is discarded.
Prefer mutating the existing instance (`app.getConnectionProperties().setBootstrapServers(...)`) when you want to keep
the YAML values.

Use `customize(domain, props -> ...)` to merge, and `put(domain, props)` only when you intend to define the whole domain
programmatically.
:::

**Option B: Define all domains in the customizer (no YAML domains)**

If you prefer full programmatic control, **omit the `reactive.commons.kafka` section entirely from your
`application.yaml`** and define all domains inside the customizer:

```java
package sample;

import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaConfig {

    private KafkaProperties loadFromSecret(String secretName) {
        // ...
        return new KafkaProperties();
    }

    @Bean
    public AsyncKafkaPropsDomain.KafkaPropsCustomizer kafkaPropsCustomizer() {
        return domainProperties -> {
            // Define all domains programmatically
            domainProperties.put("app", AsyncKafkaProps.builder()
                    .retryDelay(60000)
                    .maxRetries(3)
                    .connectionProperties(loadFromSecret("secret-app-kafka"))
                    .build());

            domainProperties.put("accounts", AsyncKafkaProps.builder()
                    .retryDelay(40000)
                    .connectionProperties(loadFromSecret("secret-accounts-kafka"))
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
- The customizer can also **add new domains** by calling `domainProperties.put("newDomain", asyncKafkaProps)`.

## Loading properties from a secret

:::danger[Deprecated]
Using `AsyncKafkaPropsDomain.KafkaSecretFiller` to load secrets is **deprecated** and will be removed in a future
version. Use **[Approach 2: Hybrid YAML + `KafkaPropsCustomizer`](#approach-2-hybrid-yaml--kafkapropscustomizer)**
instead, which provides full control over all domain properties at runtime and is the recommended way to integrate with
a secrets manager.
:::

The recommended way to load connection properties from a secrets manager is to use the `KafkaPropsCustomizer` (see
[Approach 2](#approach-2-hybrid-yaml--kafkapropscustomizer)). This gives you full control over all domain properties at
runtime. The example below uses the [Secrets Manager](https://github.com/bancolombia/secrets-manager) library.

1. Create a `@ConfigurationProperties` record to map the secret fields:

```java
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "helpers.secrets-manager")
public record SecretsManagerProperties(
        String endpoint,
        Integer cacheSize,
        Integer cacheTime,
        String kafka) {
}
```

2. Create a `KafkaConnectionProperties` record to map the fields of your secret and provide a conversion method:

```java
import org.reactivecommons.async.kafka.config.KafkaProperties;

import java.util.List;

public record KafkaConnectionProperties(String bootstrapServers) {

    public KafkaProperties toKafkaProperties() {
        var kafkaProperties = new KafkaProperties();
        kafkaProperties.setBootstrapServers(List.of(this.bootstrapServers().split(",")));
        return kafkaProperties;
    }
}
```

3. Create a `SecretsConfig` class that registers the `GenericManager` bean and exposes the Kafka secret as a bean:

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
    public KafkaConnectionProperties getSecretKafka(GenericManager connector) {
        return this.getSecret(properties.kafka(), KafkaConnectionProperties.class, connector);
    }
}
```

4. Create a separate `KafkaConfig` class that injects the `KafkaConnectionProperties` bean and defines the
   `KafkaPropsCustomizer`:

```java
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    private final KafkaConnectionProperties kafkaConnectionProperties;

    @Bean
    public AsyncKafkaPropsDomain.KafkaPropsCustomizer kafkaPropsCustomizer() {
        return domainProperties -> domainProperties.customize("app", app ->
                app.setConnectionProperties(kafkaConnectionProperties.toKafkaProperties())
        );
    }
}
```
