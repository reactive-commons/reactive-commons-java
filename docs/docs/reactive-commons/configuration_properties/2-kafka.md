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
class. There are three ways to provide these values — via `application.yaml`, programmatically, or a combination of
both — as described in the [Configuration approaches](#configuration-approaches) section below.

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

There are three ways to supply domain properties. Choose the one that best fits your use case.

### Approach 1 — YAML only

Define all domains directly in `application.yaml` as shown above. No additional Java configuration is needed.
This is the simplest approach and works well when properties do not depend on runtime values such as secrets.

### Approach 2 — Fully programmatic (no YAML domains)

Override the `AsyncKafkaPropsDomainProperties` bean to define all domains in code.
**Do not declare any domain under `reactive.commons.kafka` in your YAML when using this approach**, as both sources
would conflict.

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

### Approach 3 — Hybrid: YAML + `KafkaPropsCustomizer`

Use this approach when you want to define the domain structure in YAML (topology, retry settings, etc.) but need to
set some properties at runtime — for example, loading bootstrap servers or credentials from a secrets manager.

Declare your domains in `application.yaml` as usual, then define a `KafkaPropsCustomizer` bean to override specific
properties after the YAML is loaded. The customizer receives the full map of configured domains and can modify
any property on any domain.

:::caution[At least one domain must be declared in YAML]
The `KafkaPropsCustomizer` works **on top of** YAML-loaded domains. You must declare at least one domain under
`reactive.commons.kafka` in your `application.yaml`. If no domain is found, an `InvalidConfigurationException` will
be thrown. Do not combine this approach with an `AsyncKafkaPropsDomainProperties` `@Primary` bean.
:::

```yaml title="application.yaml"
reactive:
  commons:
    kafka:
      app:           # first domain (will be treated as the default)
        retryDelay: 60000
        maxRetries: 3
      accounts:        # second domain with independent cluster
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

**Key rules for the hybrid approach:**

- Properties set in the customizer **take precedence** over YAML values.
- YAML values not touched by the customizer are **preserved**.
- The customizer can also **add new domains** by calling `domainProperties.put("newDomain", asyncKafkaProps)`.
- The first domain declared in YAML becomes the **default domain** and automatically resolves handlers registered
  without an explicit domain (e.g., via `HandlerRegistry.register().listenEvent(...)`).

## Loading properties from a secret

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
