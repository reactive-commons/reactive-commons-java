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
          events:
            eventsSuffix: events # consumer group suffix used only when consumer.group-id is not set, group id will be like ${spring.application.name}-${eventsSuffix}
        connectionProperties: # you can override the connection properties of each domain
          bootstrap-servers: localhost:9092
          consumer:
            group-id: # optional. When set, it is used as the consumer group id of the domain events listener, exactly as provided
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
propertiesApp.

setBootstrapServers(List.of("broker1:9094", "broker2:9094"));
        propertiesApp.

getConsumer().

setGroupId("dummy.consumer-group");
propertiesApp.

getSecurity().

setProtocol("SASL_SSL");
propertiesApp.

getProperties().

put("sasl.mechanism","SCRAM-SHA-512");
```

Everything under `connectionProperties` is translated into plain Kafka client properties when the consumer, producer and
admin clients are created (`buildConsumerProperties()`, `buildProducerProperties()`, `buildAdminProperties()`). Within
the consumer, precedence is: `connectionProperties.properties` (common),`connectionProperties.consumer.*`
typed fields, `connectionProperties.consumer.properties` (raw consumer-specific).

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
2. Otherwise, the value defined by the `domain.events.eventsSuffix` properties is used, preserving the default
   configuration.

Each domain resolves its own group id, so you can authorize a different consumer group per Kafka cluster.

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
resolved as `domain.events.eventsSuffix`).

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
