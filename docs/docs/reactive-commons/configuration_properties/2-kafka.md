---
sidebar_position: 2
---

# Kafka Configuration

This page describes how to configure Kafka connection and messaging properties for each **domain** in
Reactive Commons. A domain represents an independent connection to a Kafka cluster. Your application can work
with a single domain (one cluster) or multiple domains (several independent clusters), each with its own properties.
See [Communication Scenarios](/reactive-commons-java/docs/category/communication-scenarios) for guidance on when
to use multiple domains.

All available properties are defined in the
[AsyncKafkaProps](https://github.com/reactive-commons/reactive-commons-java/blob/master/starters/async-kafka-starter/src/main/java/org/reactivecommons/async/kafka/config/props/AsyncKafkaProps.java)
class. There are two ways to provide these values via `application.yaml` or a combination of YAML and
programmatic configuration, as described in the [Configuration approaches](#configuration-approaches) section below.

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
        connectionProperties: # you can override the connection properties of each domain
          bootstrap-servers: localhost:9092
      # Another domain can be configured with same properties structure that app
      accounts: # this is a second domain name and can have another independent setup
        connectionProperties: # you can override the connection properties of each domain
          bootstrap-servers: localhost:9093
```

## Configuration approaches

There are two ways to supply domain properties. Choose the one that best fits your use case.

### Approach 1: YAML only

Define all domains directly in `application.yaml` as shown above. No additional Java configuration is needed.
This is the simplest approach and works well when properties do not depend on runtime values such as secrets.

### Approach 2: Hybrid YAML + `KafkaPropsCustomizer`

Use this approach when you want to define the domain structure in YAML (topology, retry settings, etc.) but need to
set some properties at runtime — for example, loading bootstrap servers or credentials from a secrets manager.

Declare your domains in `application.yaml` as usual, then define a `KafkaPropsCustomizer` bean to override specific
properties after the YAML is loaded. The customizer receives the full map of configured domains and can modify
any property on any domain.

:::caution[YAML domains are optional]
The `KafkaPropsCustomizer` can work with or without pre-existing YAML domains. If no domains are defined in your
`application.yaml` under `reactive.commons.kafka`, you can define all domains directly inside the customizer using
`domainProperties.put("<domain>", AsyncKafkaProps.builder()...build())`. At least one domain must exist after the
customizer
executes, otherwise an `InvalidConfigurationException` is thrown.
:::

You have two options:

**Option A: Define domains in YAML, then override with customizer**

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
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
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
            // Customize the "app" domain — overrides take precedence over YAML values
            AsyncKafkaProps app = domainProperties.get("app");
            if (app != null) {
                app.setConnectionProperties(loadFromSecret("secret-app-kafka"));
            }

            // Customize the "accounts" domain independently
            AsyncKafkaProps accounts = domainProperties.get("accounts");
            if (accounts != null) {
                accounts.setConnectionProperties(loadFromSecret("secret-accounts-kafka"));
            }
        };
    }
}
```

**Option B: Define all domains in the customizer (no YAML domains)**

If you prefer full programmatic control, **omit the `reactive.commons.kafka` section entirely from
your `application.yaml`** and define all domains
inside the customizer:

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
- YAML values not touched by the customizer are **preserved**.
- The customizer can also **add new domains** by calling `domainProperties.put("newDomain", asyncKafkaProps)`.
- The first domain declared in YAML becomes the **default domain** and automatically resolves handlers registered
  without an explicit domain (e.g., via `HandlerRegistry.register().listenEvent(...)`).

## Loading properties from a secret

:::danger[Deprecated]
Using `AsyncKafkaPropsDomain.KafkaSecretFiller` to load secrets is **deprecated** and will be removed in a future
version. Use **[Approach 2: Hybrid YAML + `KafkaPropsCustomizer`](#approach-2-hybrid-yaml--kafkapropscustomizer)**
instead, which provides full control over all domain properties at runtime and is the recommended way to integrate with
a secrets manager.
:::

The recommended way to load connection properties from a secrets manager is to use the `KafkaPropsCustomizer` (see
[Approach 2](#approach-2-hybrid-yaml--kafkapropscustomizer)). This gives you full control over all domain properties
at runtime. The example below uses the [Secrets Manager](https://github.com/bancolombia/secrets-manager) library.

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
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    private final KafkaConnectionProperties kafkaConnectionProperties;

    @Bean
    public AsyncKafkaPropsDomain.KafkaPropsCustomizer kafkaPropsCustomizer() {
        return domainProperties -> {
            AsyncKafkaProps app = domainProperties.get("app");
            if (app != null) {
                app.setConnectionProperties(kafkaConnectionProperties.toKafkaProperties());
            }
        };
    }
}
```
