---
sidebar_position: 3
---

# Kafka Schema Validation (Apicurio)

Reactive Commons can validate every Kafka message against a **JSON Schema** stored in an
[Apicurio Registry](https://www.apicur.io/registry/), both when it publishes an event and when it consumes one.

Validation is **opt-in**: without the Apicurio starter, Reactive Commons uses a no-op validator and nothing changes.

## Why a validator instead of an Apicurio SerDe

Reactive Commons keeps the Kafka wire format as raw bytes (`StringSerializer` for the key and `ByteArraySerializer`
for the value) because it needs full control of the payload to support CloudEvents, retries and DLQ. Replacing the
serdes with `JsonSchemaKafkaSerializer` / `JsonSchemaKafkaDeserializer` is therefore not possible.

Instead, the schema is resolved from the registry and applied to the payload that Reactive Commons is about to send, or
has just received. The schema coordinates travel in the record **headers**, the same mechanism the Apicurio Kafka serdes
use when `apicurio.registry.headers.enabled` is `true`. This keeps the payload as plain JSON and the messages wire
compatible in both directions with applications that still use the Apicurio Kafka serdes.

:::caution Apicurio changed the default of `apicurio.registry.headers.enabled` from `true` (2.x) to `false` (3.x). With
headers disabled the Apicurio serdes prepend a magic byte and the schema id to the payload, which is **not** compatible
with Reactive Commons. If the other side of the topic uses the Apicurio serdes, set
`apicurio.registry.headers.enabled: true` there. Reactive Commons always defaults to `true`, and **rejects at startup**
an explicit `apicurio.registry.headers.enabled: false`.
:::

## Adding the dependency

```groovy title="build.gradle"

implementation 'org.reactivecommons:async-kafka-apicurio-starter:<version>'
```

The starter transitively brings `async-commons-kafka-starter`, so it replaces it in your build file.

## Per topic configuration {#per-topic-config}

The validation is configured **per topic**: `registries` is a list of registries inside the `apicurio` block of a
domain,
each registry lists the topics validated against it, and every topic may override any property of its registry.

:::danger At least one domain must declare registries The starter exists to validate messages against an Apicurio
Registry, so having it on the classpath while **no** domain declares `registries` fails at startup: nothing would be
validated and the registry client would be created for nothing. A single domain may still be left unvalidated by
declaring no registry for it.
:::

```yaml title="application.yaml"
reactive:
   commons:
      kafka:
         app:
            connection-properties:
               bootstrap-servers: "localhost:9092"
            apicurio:
               registries:
                  - name: main-registry
                    properties:
                       apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
                       apicurio.registry.artifact.group-id: kafka
                       apicurio.registry.artifact.artifact-id:      # empty, so each topic resolves <topic>-value
                       apicurio.registry.artifact.version:          # empty, so each record keeps its own version
                       apicurio.registry.find-latest: true
                    topics:
                       - name: events-topic                        # no properties, inherits every setting above
                       - name: audit-topic
                         properties:
                            apicurio.registry.artifact.artifact-id: account   # only this topic uses that artifact
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

### The four cases it covers

**1. A topic that is not validated.** Only the declared topics are validated, so leaving `push` out of the list is
enough. Nothing else changes for the other two topics, and no request is made to any registry for `push`:

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
                       - name: events-topic
                       - name: audit-topic
                         properties:
                            apicurio.registry.artifact.artifact-id: account
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
                       - name: events-topic
                       - name: audit-topic
                         properties:
                            apicurio.registry.artifact.artifact-id: account
                  - name: secondary-registry
                    properties:
                       apicurio.registry.url: "http://localhost:9090/apis/registry/v3"
                       apicurio.registry.artifact.group-id: kafka
                       apicurio.registry.find-latest: true
                    topics:
                       - name: push
```

Each endpoint gets its own registry client and its own schema cache.

**3. Topics reading different groups of the same registry.** Same declaration as above, with the same
`apicurio.registry.url` and a different group for the topic:

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
                       - name: events-topic
                       - name: audit-topic
                         properties:
                            apicurio.registry.artifact.artifact-id: account
                  - name: secondary-registry
                    properties:
                       apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
                       apicurio.registry.artifact.group-id: kafka
                       apicurio.registry.find-latest: true
                    topics:
                       - name: push
                         properties:
                            apicurio.registry.artifact.group-id: events
```

Here **a single connection and a single schema cache serve all three topics**: the topics are grouped by what the
registry client depends on, that is the endpoint, the credentials, the TLS material and the cache tuning. The group, the
artifact and the version are resolved per record and the cache is indexed by the full coordinates, so they never split
the connection and the entries of one group never collide with those of another. The same applies across domains: two
domains resolving against the same endpoint share one client.

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
                       - name: events-topic
                       - name: audit-topic
```

### The same topic name in two domains

Topics are declared inside a domain, so the routing key is the domain plus the topic name. Two domains connected to
different clusters may declare the very same topic name against different registries, and each domain validates its own
records:

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
                       - name: audit-topic
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
                       - name: audit-topic
```

:::danger Declaring the same topic twice inside one domain fails at startup A record only carries its topic name, so
two registries of the same domain declaring the same topic leave no way to choose which one validates it. Reactive
Commons rejects it and names both registries:

```
Topic 'audit-topic' of domain app is declared by registry 'main-registry'
(reactive.commons.kafka.app.apicurio.registries[0]) and by registry 'secondary-registry'
(reactive.commons.kafka.app.apicurio.registries[1]), so it has two schema configurations and neither of them can be
chosen: a record only carries its topic name. Declare the topic once per domain, under the registry that validates it.
The same topic name may be declared by another domain, against another registry.
```

:::

### Other cases the declaration covers

Beyond the four above, these come up often and need no extra machinery:

| Case                                                                                                                                                    | How to declare it                                                                                                       |
|---------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| **Several topics honour one contract.** All of them validate the same envelope.                                                                         | Set `apicurio.registry.artifact.artifact-id` on the **registry**, and let its topics inherit it                         |
| **One topic pins a version, another follows the latest.**                                                                                               | `apicurio.registry.artifact.version` on the topic that pins it, `apicurio.registry.find-latest: true` on the registry   |
| **Schemas registered in the `default` group.**                                                                                                          | Leave `apicurio.registry.artifact.group-id` out, see [About the group id](#about-apicurioregistryartifactgroup-id)      |
| **One application validates a topic, another does not.** For instance the producer validates what it publishes while a reporting domain reads it as is. | Declare the topic where it must be validated, and leave it out where it must not                                        |
| **Raw topic listeners and notifications.** `listenTopic(...)` and `listenNotification(...)` read plain topic names.                                     | Declare those topic names like any other: the routing key is the topic of the record, whatever registered the listener  |
| **The DLQ of a topic.**                                                                                                                                 | The discarded message is republished as `<topic>.dlq`, which is a different topic. Declare it to validate it, see below |

:::caution The DLQ topic is not the topic Reactive Commons republishes a discarded message under the name of its event
plus `.dlq`, so `event.push` becomes `event.push.dlq`, and an unreadable message becomes `corruptData.dlq`. Those topics
are **not** validated unless they are declared, which is usually what you want.

Declaring them means the DLQ artifact has to exist in the registry: a schema that cannot be resolved makes the discard
itself fail, and `DLQDiscardNotifier` only logs it (`FATAL!! unable to notify Discard of message!!`), so the message is
lost. Declare the DLQ topic only when its artifact is registered.
:::

:::note A topic name misspelled in the configuration is silently not validated. Nothing else fails: Reactive Commons
cannot know which topics the application will produce or consume, so an entry that matches no topic is never used, and
the real topic keeps flowing unvalidated. Check the declared names against the event names and the
`listenTopic`/`listenNotification` registrations.
:::

### Declaring the topics programmatically

The registries live in the domain properties, so they are set from code with the very same
[`KafkaPropsCustomizer`](./2-kafka.md) used for the rest of the Kafka settings. The properties bound from the
configuration files are handed over to the customizer, which may complete them or build the whole declaration:

```java
import org.reactivecommons.async.kafka.config.props.ApicurioRegistryDefinition;
import org.reactivecommons.async.kafka.config.props.ApicurioTopicDefinition;
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
                      .registries(List.of(ApicurioRegistryDefinition.builder()
                              .name("main-registry")
                              .properties(Map.of(
                                      "apicurio.registry.url", credentials.url(),
                                      "apicurio.registry.auth.client.id", credentials.clientId(),
                                      "apicurio.registry.artifact.group-id", "kafka",
                                      "apicurio.registry.find-latest", "true"))
                              .topics(List.of(
                                      ApicurioTopicDefinition.builder().name("events-topic").build(),
                                      ApicurioTopicDefinition.builder()
                                              .name("audit-topic")
                                              .properties(Map.of(
                                                      "apicurio.registry.artifact.artifact-id", "account"))
                                              .build()))
                              .build()))
                      .build()));
   }
}
```

Use `customize(domain, ...)` rather than `put(domain, props)`, so the values already bound from the YAML are preserved.
To only complete what the configuration files declared, for instance adding credentials from a secrets manager or
appending a topic, modify the bound objects in place:

```java
return domainProperties ->domainProperties.

customize("app",props ->{
ApicurioRegistryDefinition registry = props.getApicurio().getRegistries().get(0);
    registry.

getProperties().

put("apicurio.registry.auth.client.secret",credentials.clientSecret());
        registry.

getTopics().

add(ApicurioTopicDefinition.builder().

name("push").

build());
        });
```

The customizer runs **before** any validator is built, so all the consistency checks apply to the final values.

### What is rejected at startup

| Declaration                                               | Reason                                                                                     |
|-----------------------------------------------------------|--------------------------------------------------------------------------------------------|
| The same topic under two registries of one domain         | A record only carries its topic name, so neither registry can be chosen                    |
| A registry without `topics`                               | It would open a connection to the registry without validating anything                     |
| A topic without `name`                                    | There is nothing to route the records of                                                   |
| No `apicurio.registry.url` for a topic                    | There is no registry to resolve the schema from                                            |
| No version and `apicurio.registry.find-latest` not `true` | The version resolution has to be explicit, see below                                       |
| `apicurio.registry.headers.enabled: false`                | The schema coordinates always travel in the headers                                        |
| `apicurio.registry.artifact-resolver-strategy` set        | Apicurio reads it only when a record is handed to its serdes, so it would never be invoked |
| No domain declaring `registries` at all                   | The starter would be a dependency that validates nothing                                   |

Every message names the exact path to fix, for instance
`reactive.commons.kafka.app.apicurio.registries[0].topics[1].properties`.

## The Apicurio keys

Everything the registry understands keeps its **original Apicurio key** inside `properties`, at registry level or at
topic level, so an existing serde configuration can be pasted as is and there is never a second name for the same
setting:

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
                   apicurio.registry.artifact.group-id: "kafka"      # optional, defaults to the "default" group
                   apicurio.registry.artifact.artifact-id: "person"  # optional, defaults to "<topic>-value"
                   # One of these two is REQUIRED: either pin the version, or opt into the latest one
                   apicurio.registry.artifact.version: "1"
                   apicurio.registry.find-latest: false              # default false, as in Apicurio
                   apicurio.registry.auth.client.id: "${REGISTRY_CLIENT_ID}"
                   apicurio.registry.auth.client.secret: "${REGISTRY_CLIENT_SECRET}"
                   apicurio.registry.auth.service.token.endpoint: "${REGISTRY_TOKEN_ENDPOINT}"
                topics:
                   - name: event.push
```

| Apicurio key                                 | Meaning                                                                      |
|----------------------------------------------|------------------------------------------------------------------------------|
| `apicurio.registry.serde.validation-enabled` | Turns schema validation on/off for the registry or the topic. Default `true` |
| `apicurio.registry.url`                      | Registry endpoint, **required**                                              |
| `apicurio.registry.artifact.group-id`        | Artifact group. Empty means the `default` group                              |
| `apicurio.registry.artifact.artifact-id`     | Artifact. Empty means `<topic>-value`                                        |
| `apicurio.registry.artifact.version`         | Version. Empty means "the one of each record", see below                     |
| `apicurio.registry.find-latest`              | Resolve the latest version when none is set. Default `false`                 |
| `apicurio.registry.auth.*`                   | Credentials used to reach the registry                                       |
| `apicurio.registry.request.ssl.*`            | TLS material                                                                 |

:::info The version resolution is mandatory `apicurio.registry.find-latest` defaults to `false`, exactly as in Apicurio,
so **every topic must state how the schema version is resolved**: either pin
`apicurio.registry.artifact.version`, or set `apicurio.registry.find-latest: true`, in the registry properties or in the
topic ones. Leaving both out fails at startup, see
[`apicurio.registry.find-latest`](#apicurioregistryfind-latest).
:::

:::caution `apicurio.registry.artifact-resolver-strategy` is rejected at startup Apicurio reads that property only when
a Kafka record is handed to its serdes, and Reactive Commons resolves the schema by coordinates instead, so the strategy
would be instantiated and never invoked. See
[Why the resolver strategy is not honoured](#artifact-resolver-strategy).
:::

The `properties` map accepts every key of
[
`SerdeConfig`](https://github.com/Apicurio/apicurio-registry/blob/3.3.2/serdes/generic/serde-common/src/main/java/io/apicurio/registry/serde/config/SerdeConfig.java)
and
[
`SchemaResolverConfig`](https://github.com/Apicurio/apicurio-registry/blob/3.3.2/schema-resolver/src/main/java/io/apicurio/registry/resolver/config/SchemaResolverConfig.java),
including authentication (`apicurio.registry.auth.*`), TLS for the registry client (`apicurio.registry.request.ssl.*`)
and cache tuning (`apicurio.registry.check-period-ms`).

:::note Reactive Commons uses the Apicurio Registry serdes **3.3.2**, which target the Registry **v3** API
(`/apis/registry/v3`). Only the schema resolution and validation artifacts are pulled in
(`apicurio-registry-schema-resolver`, `apicurio-registry-serde-common`, `apicurio-registry-serde-kafka-common` and
`apicurio-registry-serde-common-jsonschema`); the Kafka serdes themselves are not used.
:::

### Multiple domains

Every domain declared under `reactive.commons.kafka` carries its own `apicurio` block, so each one validates its topics
against its own registries, groups and artifacts:

```yaml title="application.yaml"
reactive:
  commons:
    kafka:
      app:
        connection-properties:
          consumer:
            group-id: my-service.consumer-group
        apicurio:
           registries:
              - name: main-registry
                properties:
                   apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
                   apicurio.registry.artifact.group-id: kafka
                   apicurio.registry.find-latest: true
                topics:
                   - name: events-topic
      accounts:
        connection-properties:
          consumer:
            group-id: my-service.consumer-group
        apicurio:
           registries:
              - name: accounts-registry
                properties:
                   apicurio.registry.url: "http://accounts-registry:8080/apis/registry/v3"
                   apicurio.registry.artifact.group-id: accounts
                   apicurio.registry.find-latest: true
                topics:
                   - name: audit-topic
```

There is no inheritance between domains: each block is self contained, which keeps the effective configuration of a
domain readable in one place. When several domains share the same registry, use a YAML anchor to avoid repeating it:

```yaml
reactive:
  commons:
    kafka:
      app:
        apicurio:
           registries:
              - name: main-registry
                properties: &registry
                   apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
                   apicurio.registry.find-latest: true
                topics:
                   - name: events-topic
      accounts:
        apicurio:
           registries:
              - name: accounts-registry
                properties:
                   <<: *registry
                   apicurio.registry.artifact.group-id: accounts
                topics:
                   - name: audit-topic
```

A single domain is left unvalidated by declaring no registry for it, and a registry or a topic is turned off with
`apicurio.registry.serde.validation-enabled: false`:

```yaml
      legacy:
         connection-properties:
            bootstrap-servers: "localhost:9092"
         # no apicurio block: legacy has no schemas registered yet
```

At least one domain of the application has to declare registries, otherwise the startup fails:

```
The async-commons-kafka-apicurio-starter dependency is present, but no domain declares
reactive.commons.kafka.<domain>.apicurio.registries, so no topic would be validated and the registry client would be
created for nothing. Declare the registries and the topics validated against them, or remove the dependency and keep
async-commons-kafka-starter. Declared domains: [app].
```

All the validators are built when the application starts, so a configuration error fails fast and the message points at
the exact property, for instance
`reactive.commons.kafka.accounts.apicurio.registries[0].topics[1].properties`.

:::note Registries resolving against the **same endpoint** share a single connection and a single schema cache, whatever
domain declares them and even when their group or artifact differ: the cache is indexed by the full coordinates, so the
entries of one group never collide with those of another. Two registries only get separate clients when their
configuration differs in something the client depends on, such as the endpoint, the credentials or the cache tuning.
:::

#### Two brokers, one registry

Nothing ties a registry to a broker, so two domains connected to **different Kafka clusters** may validate against the
same registry. It is a supported setup, and both domains will share one registry client. The thing to watch is that the
artifact of a topic defaults to `<topic>-value` inside the group of its registry, so two clusters that happen to have a
topic with the same name resolve the **same artifact** when both registries also share a group.

```yaml
reactive:
  commons:
    kafka:
      app:
        connection-properties:
          bootstrap-servers: "broker-a:9092"
        apicurio:
           registries:
              - name: registry-app
                properties:
                   apicurio.registry.url: "http://registry:8080/apis/registry/v3"
                   apicurio.registry.artifact.group-id: app   # keeps app's event.push apart from accounts'
                   apicurio.registry.find-latest: true
                topics:
                   - name: event.push
      accounts:
        connection-properties:
          bootstrap-servers: "broker-b:9092"
        apicurio:
           registries:
              - name: accounts-registry
                properties:
                   apicurio.registry.url: "http://registry:8080/apis/registry/v3"
                   apicurio.registry.artifact.group-id: accounts
                   apicurio.registry.find-latest: true
                topics:
                   - name: event.push
```

Give each registry its own `apicurio.registry.artifact.group-id` when the same topic name means different things in each
cluster, and share one group when the intention is precisely that both clusters honour a single contract.

### About `apicurio.registry.artifact.group-id`

Leaving it empty is the same as setting it to `default`: when no group is given, Apicurio's
`ArtifactReferenceImpl.build()` sets the group to the literal `"default"`, which is the group the registry uses for
artifacts that were not created inside an explicit group. Set it only if you registered your schemas under a custom
group.

### Turning the validation off with `apicurio.registry.serde.validation-enabled`

A topic is validated in both directions or not at all, and there are two ways to leave it unvalidated:

| Declaration                                                         | Effect                                                                                                                                    |
|---------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| The topic is not listed under any registry                          | No validator is built for it. The registry is never contacted for that topic, and its records are published without schema coordinates    |
| `apicurio.registry.serde.validation-enabled: false` on the topic    | Same effect, keeping the declaration in place. Useful to turn one topic off without deleting its properties                               |
| `apicurio.registry.serde.validation-enabled: false` on the registry | Every topic of that registry is left unvalidated, and no connection is opened. `apicurio.registry.url` and the credentials are not needed |

Apicurio itself reads `apicurio.registry.serde.validation-enabled`
([
`SerdeConfig.VALIDATION_ENABLED`](https://github.com/Apicurio/apicurio-registry/blob/3.3.2/serdes/generic/serde-common/src/main/java/io/apicurio/registry/serde/config/SerdeConfig.java),
default `true`) with both `JsonSchemaSerializer` and `JsonSchemaDeserializer`, so the key keeps the meaning it has
there,
applied per topic.

:::note Validating a single direction A declared topic is always validated in both directions: Reactive Commons has no
`validate-outbound` / `validate-inbound` switch, and `ApicurioSchemaValidator` itself always resolves the schema and
validates on publish and on consume. When a topic really has to be validated in one direction only, wrap it: declare a
`SchemaValidator` bean whose `validateOutbound` or `validateInbound` delegates to an `ApicurioSchemaValidator` and whose
other method is a no-op, as shown in [Per topic granularity](#per-topic-granularity).
:::

:::danger Reactive Commons **fails at startup** when `properties` sets `apicurio.registry.headers.enabled: false`.
The schema coordinates always travel in the record headers, so that property may only be set to `true`. See
[Why the schema coordinates are always written](#why-the-schema-coordinates-are-always-written).
:::

:::danger Reactive Commons also **fails at startup** when a topic does not state how the schema version resolves, that
is with an empty `apicurio.registry.artifact.version` and `apicurio.registry.find-latest` absent or `false`. See
[`apicurio.registry.find-latest`](#apicurioregistryfind-latest).
:::

:::danger Reactive Commons also **fails at startup** when `properties` sets
`apicurio.registry.artifact-resolver-strategy`. See
[Why the resolver strategy is not honoured](#artifact-resolver-strategy).
:::

:::caution An unvalidated topic is published **without the schema coordinates in its headers**, because they are
resolved by the very same validation step. The consumer of those records loses version fidelity, and an Apicurio-serdes
consumer will not be able to read them. Leaving a topic out of the declaration means giving up the coordinates.
:::

### When to leave a topic unvalidated

| Scenario                                                                                                                                           | Configuration                                                       |
|----------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------|
| **The topic has no artifact registered yet.** Progressive adoption, one topic at a time.                                                           | Do not declare the topic                                            |
| **Legacy producers that do not comply.** The records already in the topic would be rejected on consume.                                            | Do not declare the topic until the producers are migrated           |
| **DLQ reprocessor.** Its input is invalid by definition.                                                                                           | Do not declare the input topic, declare the one it republishes to   |
| **Local development and tests.** There is no registry reachable from the laptop or the CI job.                                                     | `apicurio.registry.serde.validation-enabled: false` on the registry |
| **Production incident.** An incompatible version was registered or the registry is degraded, and the flow must be restored without a redeployment. | `apicurio.registry.serde.validation-enabled: false`                 |
| **A topic the application only consumes, or only produces.**                                                                                       | Declare it: the unused direction is never invoked                   |

### Why the schema coordinates are always written

When a record is published, the resolved coordinates (`apicurio.value.groupId`, `apicurio.value.artifactId`,
`apicurio.value.version`, ...) are written into the record headers. This is **not configurable**: setting
`apicurio.registry.headers.enabled: false` in `properties` fails at startup, because the resulting behaviour would not
be the one described by that property. The property may be set, but only to `true`.

The headers are the only channel Reactive Commons has to tell the consumer *which schema version* a record was produced
with. Disabling them would not make the payload compatible with anything: the Apicurio Kafka serdes expect a magic byte
and a schema id at the start of the value, which Reactive Commons never writes. So the only real effect of not writing
them would be losing version fidelity, and that failure is silent:

- **While the schema has a single version**, everything works. The consumer falls back to the configured coordinates,
  resolves the very same artifact, and validation passes.
- **The day the schema evolves**, records already published in the topic start being validated against the **latest**
  version instead of the one they were produced with, and previously valid records begin to fail.

Because the option can only turn a working setup into one that breaks later, it is rejected instead of honoured.
Consumers that do not understand the headers simply ignore them, so writing them is always safe.

:::note Apicurio 3.x changed the default of `apicurio.registry.headers.enabled` from `true` to `false`. Reactive Commons
keeps the 2.x behaviour and always writes them, so the value is forced to `true`.
:::

### Why the resolver strategy is not honoured {#artifact-resolver-strategy}

`apicurio.registry.artifact-resolver-strategy` names the class Apicurio uses to derive the artifact of a record, for
instance `TopicIdStrategy` (`<topic>-value`) or `SimpleTopicIdStrategy` (`<topic>`). **Reactive Commons rejects the
property at startup** rather than accepting a value it cannot honour.

The strategy is read by
[
`SchemaResolver#resolveSchema(Record)`](https://github.com/Apicurio/apicurio-registry/blob/3.3.2/schema-resolver/src/main/java/io/apicurio/registry/resolver/config/SchemaResolverConfig.java),
the entry point the Apicurio serdes call with the Kafka record they are serializing. Reactive Commons never builds such
a record: it resolves the schema by coordinates with `resolveSchemaByArtifactReference`, because the artifact of a topic
is part of its configuration and must not depend on the payload. A configured strategy would therefore be instantiated
by `DefaultSchemaResolver` and never invoked, and the failure would be silent: records keep being validated against
`<topic>-value`, whatever the strategy says.

Use the configuration instead, which covers the same cases:

| Apicurio strategy       | Artifact it derives | Equivalent configuration                                                     |
|-------------------------|---------------------|------------------------------------------------------------------------------|
| `TopicIdStrategy`       | `<topic>-value`     | the default of Reactive Commons, nothing to set                              |
| `SimpleTopicIdStrategy` | `<topic>`           | `apicurio.registry.artifact.artifact-id: <topic>` on that topic              |
| a fixed artifact        | —                   | `apicurio.registry.artifact.artifact-id` on the registry or on the topic     |
| anything else           | —                   | an `ArtifactReferenceProvider` passed to `ApicurioSchemaValidator.builder()` |

:::note With one artifact name per topic, `registries` is what makes `SimpleTopicIdStrategy` unnecessary: each topic
names its own artifact, and the topics that keep the `<topic>-value` convention declare nothing.
:::

### Which artifact is used

| Situation                                                                             | Artifact resolved                                                 |
|---------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| `apicurio.registry.artifact.artifact-id` is configured                                | that artifact, for every topic                                    |
| `apicurio.registry.artifact.artifact-id` is empty                                     | `<topic>-value` (same convention as Apicurio's `TopicIdStrategy`) |
| **`apicurio.registry.artifact.version` is configured**                                | **that version, always: the headers of a record cannot move it**  |
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

:::danger The version resolution has to be explicit `find-latest` keeps the Apicurio default of `false`, so leaving it
out is the same as disabling it and a domain that also has no version **fails at startup**.

Apicurio itself would accept the combination, but not do what the flag says: with a JSON Schema its resolver cannot
derive the schema from the record, so it falls through to resolving the artifact by coordinates and, with no version,
obtains **the latest one anyway**. Rather than letting `find-latest: false` silently resolve the latest version, the
decision is required up front:

```
No schema version could be resolved for topic 'events-topic': apicurio.registry.artifact.version is empty and
apicurio.registry.find-latest is false, which is its default in Apicurio. Set apicurio.registry.artifact.version to
pin the topic to a single version, or set apicurio.registry.find-latest=true to validate against the latest one,
either in the registry properties or in reactive.commons.kafka.app.apicurio.registries[0].topics[0].properties.
```

This applies to every declared topic, whichever direction is being validated.
:::

### A producer that pins the version silently defeats `find-latest` downstream

`apicurio.registry.artifact.version` and `apicurio.registry.find-latest` are evaluated **per topic of each
application**, so nothing stops the producer of a topic from pinning a version while a consumer of that same topic
configures `find-latest: true`, expecting to always validate against the newest schema. That combination does not work
the way it looks:

1. The producer's topic has `apicurio.registry.artifact.version: 1` (or any other value) configured, so every outbound
   record is validated against version 1 and, since a pinned version always wins, `find-latest` on the producer's own
   topic is irrelevant here (see the table above).
2. On every publish, Reactive Commons writes the coordinates it resolved into the record headers, including
   `apicurio.value.version=1` — this happens on **every** message, whether it was sent as a `DomainEvent`, a
   `CloudEvent`, or a raw message built by hand (`KafkaMessage`), because all three go through the same
   `validateOutbound` step.
3. The consumer's topic has `apicurio.registry.artifact.version` empty and `find-latest: true`, which looks correct in
   isolation. But the record already carries `apicurio.value.version=1` in its headers, and the artifact/group it names
   matches the one configured for that topic, so the consumer takes the version **from the headers** instead of asking
   the registry for the latest one (see [Which artifact is used](#which-artifact-is-used), second row).
   `find-latest` is never consulted for that message.

The net effect: the consumer keeps validating against version 1 forever, no matter how many new versions are registered,
until the pinned version is removed from the **producer's** configuration. This is easy to miss because the consumer's
own configuration looks correct, and `find-latest` genuinely does apply to messages that carry no schema coordinates at
all — for instance ones published by a client that is not Reactive Commons and does not write those headers.

To have both sides really resolve the latest version, leave `apicurio.registry.artifact.version` empty **on the
producer's topic too**:

```yaml title="Producer: resolves and stamps the latest version on every publish"
reactive:
  commons:
    kafka:
      app:
        apicurio:
           registries:
              - name: main-registry
                properties:
                   apicurio.registry.url: "http://localhost:8080/apis/registry/v3"
                   apicurio.registry.artifact.version:        # empty, not pinned
                   apicurio.registry.find-latest: true
                topics:
                   - name: event.push
```

With that change, the producer resolves the actual latest version at publish time and writes that into the headers, so a
consumer configured the same way keeps following the real latest version as the schema evolves.

:::tip Prefer an explicit version Of the ways to determine a version, `apicurio.registry.artifact.version` is the only
one that does not depend on what the registry considers latest at any given moment. Set it when the topic must honour a
single contract.
:::

:::danger Why the headers cannot choose the artifact The headers are written by whoever produced the record, so trusting
them completely would mean letting the producer choose the schema its own payload is validated against: pointing them at
a permissive artifact registered anywhere in the registry turns inbound validation into a no-op, and pointing every
record at a different artifact turns the consumer into an amplifier of requests against the registry.
:::

#### Records identified by content, not by name

A content id, a global id and a content hash identify a schema by its content, so the registry cannot tell which
artifact they belong to: resolving one of them returns the schema and nothing else, with no way to check that it is the
artifact of the topic. Those coordinates are therefore always discarded.

| Who produces the records                                       | What travels in the headers | How it is validated                        |
|----------------------------------------------------------------|-----------------------------|--------------------------------------------|
| Reactive Commons                                               | group, artifact and version | Against the version of the record          |
| Apicurio Kafka serdes, default configuration                   | **content id**              | Against the version this domain configured |
| Apicurio Kafka serdes with `apicurio.registry.use-id=globalId` | global id                   | Against the version this domain configured |

A Reactive Commons producer is therefore validated with full version fidelity, while records coming from the Apicurio
serdes are validated against the contract the consumer declared. Since the version a domain accepts is explicit, this is
the intended behaviour: a record published against another version is rejected instead of silently validated against it.

The resolved schemas are **cached** by the Apicurio schema resolver, so the registry is not called for every message.

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

:::tip Keep the registry close to the application, and lower `check-period-ms` only if a new latest version has to be
picked up quickly.
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
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "name": {
      "type": "string"
    },
    "eventId": {
      "type": "string"
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
          "type": "string"
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

Validation runs in `GenericMessageListener.handle(...)` *before* the handler is invoked and *outside* the
`retryWhen(...)` operator, so `maxRetries` and `retryDelay` do not apply to it. This is intentional: an invalid payload
will never become valid by being processed again, so retrying it only delays the inevitable and wastes consumer
throughput.

The record therefore goes straight to the fallback strategy on the very first attempt:

| `maxRetries`     | Behaviour on schema validation failure                                      |
|------------------|-----------------------------------------------------------------------------|
| `>= 0` (default) | `DEFINITIVE_DISCARD`: sent to the DLQ (or acknowledged if the DLQ is off)   |
| `-1`             | `FAST_RETRY`: **infinite** re-delivery loop, the message is never discarded |

:::warning Do not use `maxRetries = -1` together with schema validation. That setting means *infinite fast retries*, and
an invalid payload will be redelivered forever, blocking the partition.
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
[the properties](#multiple-domains) are not enough, declare a `DomainSchemaValidatorProvider` instead:

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

:::note With the per topic configuration, the validator of a domain is a `TopicSchemaValidatorRouter` that applies the
validator of the topic of each record, and leaves the topics the domain does not declare unvalidated. Each domain gets
its own router, so the same topic name may belong to another registry in another domain. A
`DomainSchemaValidatorProvider` or a `SchemaValidator` bean still replaces it entirely.
:::

