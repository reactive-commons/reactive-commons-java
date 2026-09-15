---
sidebar_position: 3
---

# Kafka Schema Validation (Apicurio)

Reactive Commons can validate every Kafka message against a **JSON Schema** stored in an
[Apicurio Registry](https://www.apicur.io/registry/), both when it publishes an event and when it consumes one.

Validation is **opt-in**: without the Apicurio starter, Reactive Commons uses a no-op validator and nothing changes.

## Adding the dependency

```groovy title="build.gradle"
implementation 'org.reactivecommons:async-kafka-apicurio-starter:<version>'
```

The starter transitively brings `async-commons-kafka-starter`, so it replaces it in your build file.

## Per topic configuration {#per-topic-config}

The validation is configured **per topic**: `registries` is a list of registries inside the `apicurio` block of a
domain,
each registry lists the topics validated against it, and every topic may override any property of its registry.

```yaml title="application.yaml"
reactive:
   commons:
      kafka:
         app: # the default domain
            connection-properties:
               bootstrap-servers: "localhost:9092"
            apicurio:
               registries:
                  - name: main-registry
                    properties: # every Apicurio setting, with its original key
                       apicurio.registry.serde.validation-enabled: true  # default true, set to false to skip validation
                       apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
                       apicurio.registry.artifact.group-id: "kafka"     # optional, defaults to the "default" group
                       apicurio.registry.artifact.artifact-id: "person" # optional, defaults to "<topic>-value"
                       # One of these two is REQUIRED: either pin the version, or opt into the latest one
                       apicurio.registry.artifact.version: "1"
                       apicurio.registry.find-latest: false # default false, as in Apicurio
                       apicurio.registry.auth.client.id: "${REGISTRY_CLIENT_ID}"
                       apicurio.registry.auth.client.secret: "${REGISTRY_CLIENT_SECRET}"
                       apicurio.registry.auth.service.token.endpoint: "${REGISTRY_TOKEN_ENDPOINT}"
                    topics:
                     - name: events # no properties, inherits every setting above
                     - name: audit
                       properties:
                          apicurio.registry.artifact.artifact-id: accounts # only this topic uses that artifact
```

The properties are inherited along the declaration: the ones of a **registry** are the defaults of every **topic** it
declares, and a topic overrides them key by key. The keys are the Apicurio ones, and the same startup checks apply to
every topic: the registry URL is required, the version resolution has to be explicit, and
`apicurio.registry.headers.enabled` may only be `true`.

`name` identifies the registry in the error messages; it does not have to match anything in Apicurio. Two registries may
even point at the same endpoint, which is the way to give a group of topics its own group id.

| Property        | Level           | Meaning                                                                  |
|-----------------|-----------------|--------------------------------------------------------------------------|
| `name`          | registry        | Name reported when a declaration is invalid                              |
| `properties`    | registry, topic | Apicurio settings. The ones of a topic win over the ones of its registry |
| `topics[].name` | topic           | Kafka topic name, as it travels in the record                            |

A declared topic is validated **in both directions**: on publish, which is also what writes the schema coordinates in
the record headers, and on consume. A topic that is not declared, or that sets
`apicurio.registry.serde.validation-enabled: false`, is not validated at all and its records carry no coordinates.

### Scenarios it covers

**1. A topic that is not validated.** Say you have 3 topics: `events`, `audit` and `push`. Only the
declared topics are validated, so leaving `push` out of the list is enough. Nothing else changes for the other two
topics, and no request is made to any registry for `push`:

```yaml
reactive:
   commons:
      kafka:
         app:
            apicurio:
               registries:
                  - name: main-registry
                    properties:
                       apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
                       apicurio.registry.artifact.group-id: kafka
                       apicurio.registry.find-latest: true
                    topics:
                       - name: events
                       - name: audit
                         properties:
                            apicurio.registry.artifact.artifact-id: accounts
```

A declared topic can also be turned off without removing it, with
`apicurio.registry.serde.validation-enabled: false` in its own `properties`.

**2. Topics pointing at different registries.** Declare one registry per endpoint and list its topics:

```yaml
reactive:
   commons:
      kafka:
         app:
            apicurio:
               registries:
                  - name: main-registry
                    properties:
                       apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
                       apicurio.registry.artifact.group-id: kafka
                       apicurio.registry.find-latest: true
                    topics:
                       - name: events
                       - name: audit
                         properties:
                            apicurio.registry.artifact.artifact-id: accounts
                  - name: secondary-registry
                    properties:
                       apicurio.registry.url: "http://localhost:9090/apis/registry/v3"
                       apicurio.registry.artifact.group-id: kafka
                       apicurio.registry.find-latest: true
                    topics:
                       - name: push
```

Each endpoint gets its own registry client and its own schema cache.

**3. Topics reading different groups of the same registry, with every property inherited otherwise.** A topic
overrides only `apicurio.registry.artifact.group-id`, inheriting the rest from its registry:

```yaml
reactive:
   commons:
      kafka:
         app:
            apicurio:
               registries:
                  - name: main-registry
                    properties:
                       apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
                       apicurio.registry.artifact.group-id: kafka
                       apicurio.registry.find-latest: true
                    topics:
                       - name: events
                       - name: audit
                         properties:
                            apicurio.registry.artifact.artifact-id: accounts
                       - name: push
                         properties:
                            apicurio.registry.artifact.group-id: notifications
```

Here **a single connection and a single schema cache serve all three topics**: `apicurio.registry.artifact.group-id`
is not part of what the registry client depends on, so it never splits the connection. The group, the artifact and the
version are resolved per record and the cache is indexed by the full coordinates, so the entries of one group never
collide with those of another. The same applies across domains: two domains resolving against the same endpoint share
one client.

**4. Every topic inheriting the properties it does not declare.** A topic with no `properties` uses those of its
registry, which in turn inherit those of the domain, and its artifact defaults to `<topic>-value`:

```yaml
reactive:
   commons:
      kafka:
         app:
            apicurio:
               registries:
                  - name: main-registry
                    properties:
                       apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
                       apicurio.registry.artifact.group-id: kafka
                       apicurio.registry.find-latest: true

                    topics:
                       - name: events
                       - name: audit
```

**5. The same topic name in two domains:** Topics are declared inside a domain, so the routing key is the domain plus 
the topic name. Two domains connected to different clusters may declare the very same topic name against different
registries, and each domain validates its own records:

```yaml
reactive:
   commons:
      kafka:
         app:
            connection-properties:
               bootstrap-servers: "broker-a:9092"
            apicurio:
               registries:
                  - name: main-registry
                    properties:
                       apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
                       apicurio.registry.find-latest: true
                    topics:
                       - name: audit
         accounts:
            connection-properties:
               bootstrap-servers: "broker-b:9092"
            apicurio:
               registries:
                  - name: accounts-registry
                    properties:
                       apicurio.registry.url: "http://accounts-registry:8080/apis/registry/v3"
                       apicurio.registry.find-latest: true
                    topics:
                       - name: audit
```

:::danger Declaring the same topic twice inside one domain fails at startup
:::

### Declaring the topics programmatically

The registries live in the domain properties, so they are set from code with the very same
[`KafkaPropsCustomizer`](./2-kafka.md#approach-2-hybrid-yaml--kafkapropscustomizer) used for the rest of the Kafka settings. The properties bound from the
configuration files are handed over to the customizer, which may complete them or build the whole declaration:

```java
import org.reactivecommons.async.kafka.config.props.ApicurioRegistry;
import org.reactivecommons.async.kafka.config.props.ApicurioTopic;
import org.reactivecommons.async.kafka.config.props.ApicurioValidationProperties;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class ApicurioTopicsConfig {

   @Bean
   public AsyncKafkaPropsDomain.KafkaPropsCustomizer kafkaPropsCustomizer(RegistryCredentials credentials) {
      return domainProperties -> domainProperties.customize("app", props ->
              props.setApicurio(ApicurioValidationProperties.builder()
                      .registries(List.of(ApicurioRegistry.builder()
                              .name("main-registry")
                              .properties(Map.of(
                                      "apicurio.registry.url", credentials.url(),
                                      "apicurio.registry.auth.client.id", credentials.clientId(),
                                      "apicurio.registry.artifact.group-id", "kafka",
                                      "apicurio.registry.find-latest", "true"))
                              .topics(List.of(
                                      ApicurioTopic.builder().name("events").build(),
                                      ApicurioTopic.builder()
                                              .name("audit-topic")
                                              .properties(Map.of(
                                                      "apicurio.registry.artifact.artifact-id", "accounts"))
                                              .build()))
                              .build()))
                      .build()));
   }
}
```

Use `customize(domain, ...)` rather than `put(domain, props)`, so the values already bound from the YAML are preserved.
The customizer runs **before** any validator is built, so all the consistency checks apply to the final values.


## What is rejected at startup

| Declaration                                                                                                         | Reason                                                                                     |
|---------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------|
| The same topic under two registries of one domain                                                                   | A record only carries its topic name, so neither registry can be chosen                    |
| A registry without `topics`                                                                                         | It would open a connection to the registry without validating anything                     |
| A topic without `name`                                                                                              | There is nothing to route the records of                                                   |
| No `apicurio.registry.url` for a topic                                                                              | There is no registry to resolve the schema from                                            |
| No `apicurio.registry.artifact.version` and `apicurio.registry.find-latest` not `true`                              | The version resolution has to be explicit, see below                                       |
| `apicurio.registry.headers.enabled: false`                                                                          | The schema coordinates always travel in the headers                                        |
| `apicurio.registry.artifact-resolver-strategy` set to anything other than `TopicIdStrategy`/`SimpleTopicIdStrategy` | Apicurio reads it only when a record is handed to its serdes, so it would never be invoked |
| No domain declaring `registries` at all                                                                             | The starter would be a dependency that validates nothing                                   |

Every message names the exact path to fix, for instance
`reactive.commons.kafka.app.apicurio.registries[0].topics[1].properties`.

## Why a validator instead of an Apicurio SerDe

Reactive Commons keeps the Kafka wire format as raw bytes (`StringSerializer` for the key and `ByteArraySerializer`
for the value) because it needs full control of the payload to support CloudEvents, retries and DLQ. Replacing the
serdes with `JsonSchemaKafkaSerializer` / `JsonSchemaKafkaDeserializer` is therefore not possible.

Instead, the schema is resolved from the registry and applied to the payload that Reactive Commons is about to send, or
has just received. The schema coordinates travel in the record **headers**, the same mechanism the Apicurio Kafka serdes
use when `apicurio.registry.headers.enabled` is `true`. This keeps the payload as plain JSON and the messages wire
compatible in both directions with applications that still use the Apicurio Kafka serdes.

## The Apicurio keys

### Turning the validation off with `apicurio.registry.serde.validation-enabled`

A topic is validated in both directions or not at all, and there are two ways to leave it unvalidated:

| Declaration                                                         | Effect                                                                                                                                    |
|---------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| The topic is not listed under any registry                          | No validator is built for it. The registry is never contacted for that topic, and its records are published without schema coordinates    |
| `apicurio.registry.serde.validation-enabled: false` on the topic    | Same effect, keeping the declaration in place. Useful to turn one topic off without deleting its properties                               |
| `apicurio.registry.serde.validation-enabled: false` on the registry | Every topic of that registry is left unvalidated, and no connection is opened. `apicurio.registry.url` and the credentials are not needed |

Apicurio itself reads `apicurio.registry.serde.validation-enabled`
([
`SerdeConfig.VALIDATION_ENABLED`](https://github.com/Apicurio/apicurio-registry/blob/main/serdes/generic/serde-common/src/main/java/io/apicurio/registry/serde/config/SerdeConfig.java),
default `true`) with both `JsonSchemaSerializer` and `JsonSchemaDeserializer`, so the key keeps the meaning it has
there, applied per topic.

### `apicurio.registry.artifact-resolver-strategy` {#artifact-resolver-strategy}

`apicurio.registry.artifact-resolver-strategy` names the convention Apicurio's own serdes use to derive the artifact
id from a Kafka record, by calling
[
`SchemaResolver#resolveSchema(Record)`](https://github.com/Apicurio/apicurio-registry/blob/main/schema-resolver/src/main/java/io/apicurio/registry/resolver/config/SchemaResolverConfig.java).
Reactive Commons never builds such a record: it resolves the schema by coordinates with
`resolveSchemaByArtifactReference`. Two of Apicurio's strategies only need the **topic name** to decide the artifact
id, so Reactive Commons reproduces their result itself instead of invoking the strategy class:

| Apicurio strategy       | Artifact it derives | How Reactive Commons reproduces it                                        |
|-------------------------|----------------------|------------------------------------------------------------------------------|
| `TopicIdStrategy`       | `<topic>-value`      | the default, used when the property is absent or set to this strategy       |
| `SimpleTopicIdStrategy` | `<topic>`            | used when the property is set to this strategy                             |

```yaml
apicurio:
  registries:
    - name: main-registry
      properties:
        apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
        apicurio.registry.find-latest: true
      topics:
        - name: event.push
          properties:
            # Resolves the artifact id as "event.push", not "event.push-value"
            apicurio.registry.artifact-resolver-strategy: io.apicurio.registry.serde.strategy.SimpleTopicIdStrategy
```

`apicurio.registry.artifact.artifact-id`, when set, still wins over either strategy: it names one fixed artifact for
every topic using that configuration, instead of a convention derived from the topic name.

### Which artifact is used

| Situation                                                                             | Artifact resolved                                                 |
|---------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| `apicurio.registry.artifact.artifact-id` is configured                                | that artifact, for every topic                                    |
| `apicurio.registry.artifact.artifact-id` is empty                                     | `<topic>-value` (same convention as Apicurio's `TopicIdStrategy`) |
| `apicurio.registry.artifact.version` is configured                                    | that version, always: the headers of a record cannot move it      |
| `apicurio.registry.artifact.version` is empty and the headers name the artifact above | the same artifact, at the **version** of the headers              |
| `apicurio.registry.artifact.version` is empty and the record carries no version       | the **latest** version of the artifact                            |

On the consumer side the artifact is always the one configured for the topic. The **version** is the only thing that may
come from the record headers, and only when it is not pinned in the configuration, so a message keeps being validated
against the very same schema version its producer used while it can never point the consumer somewhere else.

:::caution Pinning `apicurio.registry.artifact.version` disables the version fidelity Setting
`apicurio.registry.artifact.version` declares the single contract the topic accepts, so **every** record is validated
against it no matter what its headers say. That is what you want to enforce one version; leave
`apicurio.registry.artifact.version` empty for each record to be validated against the version it was published with,
which is what keeps old records valid after the schema evolves.
:::

### `apicurio.registry.find-latest`

It keeps the meaning **and the default** it has in Apicurio: it resolves the latest version of the artifact when no
explicit version is configured, and it is `false` unless stated otherwise.

| Configuration                                  | Version resolved                          |
|------------------------------------------------|-------------------------------------------|
| `apicurio.registry.artifact.version` is set    | that version, `find-latest` is irrelevant |
| version empty, `find-latest: true`             | the latest version of the artifact        |
| version empty, `find-latest` absent or `false` | **rejected at startup**                   |

### Remote `$ref` are not downloaded

A schema is only allowed to reference the artifacts the registry itself resolves as references. A `$ref` pointing at an
arbitrary URL is rejected while the schema is being parsed, instead of making the application download it, and every
reference is resolved at that moment rather than during the validation of the first message.

### Blocking behaviour and cache lifetime

Resolving a schema is a **blocking** HTTP call issued from the thread that publishes or consumes the record, and the
Apicurio registry client does not apply a request timeout, so an unreachable registry can hold that thread. Two defaults
are therefore changed with respect to the Apicurio serdes, both overridable through `properties`:

| Property                                   | Apicurio default | Reactive Commons default | Reason                                                                                                                 |
|--------------------------------------------|------------------|--------------------------|------------------------------------------------------------------------------------------------------------------------|
| `apicurio.registry.check-period-ms`        | `30000`          | `1800000`                | A registered version is immutable, so re-resolving it twice a minute only puts a blocking call back into the hot path. |
| `apicurio.registry.fault-tolerant-refresh` | `false`          | `true`                   | A registry that blinks while an entry is refreshed keeps serving the cached schema instead of failing the message.     |

:::tip Keep the registry close to the application, and lower `check-period-ms` only if a new latest version has to be  picked up quickly.
:::

## What is validated: the whole record value

Reactive Commons validates the **exact bytes** that travel in the record value, which is the Reactive Commons envelope,
not the domain payload alone. For a `DomainEvent` the value published to the topic is:

```json
{
  "name": "event.push",
  "eventId": "9894f4a7-4cdb-4fd0-8314-c7514f71bf76",
  "data": {
    "title": "Notification title",
    "message": "Hello",
    "dateSend": "2026-08-29T17:43:55.888052"
  }
}
```

:::caution The artifact registered in Apicurio must therefore describe the **envelope**, not only the contents of`data`.
Registering the domain schema alone is the most common mistake: with `"additionalProperties": false` it fails with
`required property 'title' not found` plus `property 'name'/'eventId'/'data' is not defined in the schema`, because the
validator is comparing the envelope against a schema written for `data`.
:::

Wrap your domain schema like this:

```json title="event.push-value"
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://empresa.com/schemas/event-push.json",
  "title": "EventPush",
  "type": "object",
  "properties": {
    "name": {
      "type": "string",
      "const": "event.push"
    },
    "eventId": {
      "type": "string",
      "format": "uuid"
    },
    "data": {
      "type": "object",
      "properties": {
        "title": {
          "type": "string"
        },
        "message": {
          "type": "string"
        },
        "dateSend": {
          "type": "string",
          "format": "date-time"
        }
      },
      "required": [
        "title",
        "message",
        "dateSend"
      ],
      "additionalProperties": false
    }
  },
  "required": [
    "name",
    "eventId",
    "data"
  ],
  "additionalProperties": false
}
```

When the event is emitted as a **CloudEvent** the record value is the CloudEvent itself in structured mode
(`application/cloudevents+json`), so the schema must describe the CloudEvent attributes and its `data` member.

## What happens when a message does not comply

A `SchemaValidationException` is raised **per message**, so an invalid record never blocks the rest of the partition.

### Producing

The `Mono` returned by `DomainEventBus.emit(...)` fails and nothing is written to the topic. The error surfaces to your
own code, which decides what to do with it.

### Consuming

The record is **not** delivered to the handler and, most importantly, **schema validation is never retried**.

Schema validation happens before the handler runs, and it is never retried: `maxRetries` and `retryDelay` do not apply
to it. This is intentional: an invalid payload will never become valid by being processed again, so retrying it only
delays the inevitable and wastes consumer throughput.

The record therefore goes straight to the fallback strategy on the very first attempt:

| `maxRetries`     | Behaviour on schema validation failure                                      |
|------------------|-----------------------------------------------------------------------------|
| `>= 0` (default) | `DEFINITIVE_DISCARD`: sent to the DLQ (or acknowledged if the DLQ is off)   |
| `-1`             | `FAST_RETRY`: **infinite** re-delivery loop, the message is never discarded |

:::warning Do not use `maxRetries = -1` together with schema validation
That setting means *infinite fast retries*, and an invalid payload will be redelivered forever, blocking the partition.
:::

## Customizing the validator

There are two extension points, resolved in this order:

| Bean                            | Scope                                            | Effect                                                                   |
|---------------------------------|--------------------------------------------------|--------------------------------------------------------------------------|
| `SchemaValidator`               | Global, one instance shared by every domain      | Wins over everything else, the `apicurio` properties are not read        |
| `DomainSchemaValidatorProvider` | Per domain, asked once for each connected domain | Replaces the provider of the starter, the properties are not read either |
| *(none)*                        | —                                                | The starter builds the Apicurio validators from the properties           |

If neither bean is declared and validation is disabled, Reactive Commons falls back to `NoOpSchemaValidator`.

Any bean of type `SchemaValidator` replaces the default one, so a fully custom implementation can be provided:

```java
import org.apache.kafka.common.header.Headers;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SchemaValidationConfig {

    @Bean
    public SchemaValidator schemaValidator() {
        return new SchemaValidator() {
            @Override
            public void validateOutbound(String topic, byte[] payload, Headers headers) {
                // custom validation, may also enrich headers
            }

            @Override
            public void validateInbound(String topic, byte[] payload, Headers headers) {
                // custom validation
            }
        };
    }
}
```

To keep the Apicurio behaviour but change how the artifact is chosen per topic, declare an
`ArtifactReferenceProvider` and build the validator with `ApicurioSchemaValidator.builder()`.

### Choosing the validator per domain

A `SchemaValidator` bean applies to every domain. When the decision depends on the domain and
[the properties](#per-topic-config) are not enough, declare a `DomainSchemaValidatorProvider` instead:

```java
import org.reactivecommons.async.kafka.validation.DomainSchemaValidatorProvider;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainSchemaValidationConfig {

    @Bean
    public DomainSchemaValidatorProvider domainSchemaValidatorProvider(SchemaValidator strict) {
        return domain -> "legacy".equals(domain) ? NoOpSchemaValidator.INSTANCE : strict;
    }
}
```

The provider is asked once per domain while the connections are being created, so it does not need to cache anything,
but it should return the same instance for the same domain to avoid duplicating the schema cache. Returning `null` is
allowed and means "no validation for this domain".

:::caution A validator built with `ApicurioSchemaValidatorFactory` holds a registry client, so it implements`Closeable`.
The provider of the starter releases the clients it created when the context is disposed; a custom
`SchemaValidator` or `DomainSchemaValidatorProvider` bean is responsible for its own, either by implementing
`AutoCloseable` (Spring infers `close` as the destroy method) or by declaring `@Bean(destroyMethod = "close")`.

To build several validators over a single connection and cache, create the resolver once with
`ApicurioSchemaValidatorFactory.createResolver(configs)` and pass it to
`ApicurioSchemaValidatorFactory.create(resolver, configs, ...)`. A validator built that way does **not** own the
resolver, so closing it leaves the resolver usable for the other domains and releasing it stays with the caller.
:::

### Per topic granularity

Declaring the topics under [`registries`](#per-topic-config) already gives each topic its own registry, group,
artifact and directions, so a custom bean is not needed for that. What the properties cannot express is a rule that
depends on something else than the topic name, for instance the payload or a feature flag. In that case delegate to a
validator built with `ApicurioSchemaValidatorFactory.create(...)`, which takes the very same Apicurio keys used in
`properties`:

```java
import io.apicurio.registry.resolver.config.SchemaResolverConfig;
import org.apache.kafka.common.header.Headers;
import org.reactivecommons.async.kafka.apicurio.ApicurioSchemaValidatorFactory;
import org.reactivecommons.async.kafka.validation.SchemaValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

@Configuration
public class SelectiveSchemaValidationConfig {

    private static final Set<String> VALIDATED_ON_PUBLISH = Set.of("event.audit");

    @Bean
    public SchemaValidator schemaValidator(@Value("${apicurio.url}") String url) {
        SchemaValidator delegate = ApicurioSchemaValidatorFactory.create(
                Map.of(SchemaResolverConfig.REGISTRY_URL, url));

        return new SchemaValidator() {
            @Override
            public void validateOutbound(String topic, byte[] payload, Headers headers) {
                if (VALIDATED_ON_PUBLISH.contains(topic)) {
                    delegate.validateOutbound(topic, payload, headers);
                }
            }

            @Override
            public void validateInbound(String topic, byte[] payload, Headers headers) {
                delegate.validateInbound(topic, payload, headers);
            }
        };
    }
}
```

Because the bean is declared with the plain `SchemaValidator` type, the one from the starter backs off
(`@ConditionalOnMissingBean`) and the `apicurio` properties are no longer read: the delegate owns the whole
configuration. Skipping `validateOutbound` for a topic also skips writing its schema coordinates in the headers.

If what changes per topic is only the artifact, do not write a custom `SchemaValidator`: declare the topic under
[`registries`](#per-topic-config) with its own `apicurio.registry.artifact.artifact-id`, or implement
`ArtifactReferenceProvider` and pass it to `ApicurioSchemaValidator.builder()`.
