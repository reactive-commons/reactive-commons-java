---
sidebar_position: 1
---

# Getting Started

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

<Tabs>
  <TabItem value="rabbitmq" label="RabbitMQ" default>

This quick start tutorial sets up a single node RabbitMQ and runs the sample reactive sender and consumer using Reactive
Commons.

## Requirements

You need Java JRE installed (Java 17 or later).

You also need to install RabbitMQ. Follow the [instructions from the website](https://www.rabbitmq.com/download.html).

## Start RabbitMQ

Start RabbitMQ on your local machine with all the defaults (e.g. AMQP port is 5672).

### Containerized

You can run it with Docker or Podman

```shell
podman run -d -p 5672:5672 -p 15672:15672 --name rabbitmq rabbitmq:management-alpine
```

## Spring Boot Application

The Spring Boot sample publishes and consumes messages with the `DomainEventBus`. This application illustrates how to
configure Reactive Commons using RabbitMQ in a Spring Boot environment.

To build your own application using the Reactive Commons API, you need to include a dependency to Reactive Commons.

### Current version

![Maven metadata URL](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Forg%2Freactivecommons%2Fasync-commons-rabbit-starter%2Fmaven-metadata.xml)

### Dependency

```groovy
dependencies {
    implementation "org.reactivecommons:async-commons-rabbit-starter:<version>"
}
```

:::tip
If you will use Cloud Events, you should include the Cloud Events dependency:

```groovy
dependencies {
    implementation 'io.cloudevents:cloudevents-json-jackson:4.0.1'
}
```

:::

### Configuration properties

Also you need to include the name for your app in the `application.properties`, it is important because this value will
be used
to name the application queues inside RabbitMQ:

```properties
spring.application.name=MyAppName
```

Or in your `application.yaml`

```yaml
spring:
  application:
    name: MyAppName
```

You can set the RabbitMQ connection properties through spring boot with
the [`spring.rabbitmq.*` properties](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html)

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    virtual-host: /
```

You can also set it in runtime for example from a secret, so you can create the `RabbitProperties` bean like:

```java title="org.reactivecommons.async.rabbit.config.RabbitProperties"

@Configuration
public class MyRabbitMQConfig {

    @Bean
    @Primary
    public RabbitProperties customRabbitProperties() {
        RabbitProperties properties = new RabbitProperties();
        properties.setHost("localhost");
        properties.setPort(5672);
        properties.setVirtualHost("/");
        properties.setUsername("guest");
        properties.setPassword("guest");
        return properties;
    }
}
```

Please refer to [Configuration Properties](/reactive-commons-java/docs/reactive-commons/configuration-properties)
Or with secrets [Loading properties from a secret](/reactive-commons-java/docs/reactive-commons/configuration-properties#loading-properties-from-a-secret)

The 5.x.x stable version of Reactive Commons has been merged with the deprecated `-eda` variant, this means that
the `async-commons-rabbit-starter` artifact is now the only one to use.

This merge allows you to:

### Multi Broker Instances of RabbitMQ or Multi Domain support

Enables to you the ability to listen events from different domains, send commands to different domains and make queries
to different domains.

### Cloud Events

Includes the Cloud Events specification.

If you want to use it, you should read the [Creating a CloudEvent guide](11-creating-a-cloud-event.md)

  </TabItem>
  <TabItem value="kafka" label="Kafka">
    This quick start tutorial sets up a single node Kafka and runs the sample reactive sender and consumer using Reactive
Commons.

## Requirements

You need Java JRE installed (Java 17 or later).

## Start Kafka

Start a Kafka broker on your local machine with all the defaults (e.g. port is 9092).

### Containerized

You can run it with Docker or Podman.

The following docker compose has a Kafka broker, a Zookeeper and a Kafka UI.

docker-compose.yml
```yaml
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.4.1
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:7.4.1
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
    ports:
      - "9092:9092"
    depends_on:
      - zookeeper

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
      KAFKA_CLUSTERS_0_ZOOKEEPER: zookeeper:2181
    ports:
      - "8081:8080"
    depends_on:
      - kafka
```

```shell
docker-compose up
```

You may set in /etc/hosts (or equivalent) the following entry:

```txt
127.0.0.1 kafka
```

To enter the Kafka UI, open your browser and go to `http://localhost:8081`

## Spring Boot Application

The Spring Boot sample publishes and consumes messages with the `DomainEventBus`. This application illustrates how to
configure Reactive Commons using Kafka in a Spring Boot environment.

To build your own application using the Reactive Commons API, you need to include a dependency to Reactive Commons.

### Current version

![Maven metadata URL](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Forg%2Freactivecommons%2Fasync-commons-rabbit-starter%2Fmaven-metadata.xml)

### Dependency

```groovy
dependencies {
    implementation "org.reactivecommons:async-kafka-starter:<version>"
}
```

Note: If you will use Cloud Events, you should include the Cloud Events dependency:

```groovy
dependencies {
    implementation 'io.cloudevents:cloudevents-json-jackson:4.0.1'
}
```

### Configuration properties

Also you need to include the name for your app in the `application.properties`, it is important because this value will
be used
to name the application group-id inside Kafka:

```properties
spring.application.name=MyAppName
```

Or in your `application.yaml`

```yaml
spring:
  application:
    name: MyAppName
```

You can set the Kafka connection properties through spring boot with
the [`spring.kafka.*` properties](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html)

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
```

You can also set it in runtime for example from a secret, so you can create the `KafkaProperties` bean like:

```java title="org.reactivecommons.async.kafka.config.KafkaProperties"

@Configuration
public class MyKafkaConfig {

    @Bean
    @Primary
    public KafkaProperties myRCKafkaProperties() {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("localhost:9092"));
        return properties;
    }
}
```

### Multi Broker Instances of Kafka or Multi Domain support

Enables to you the ability to listen events from different domains.

### Cloud Events

Includes the Cloud Events specification.

If you want to use it, you should read the [Creating a CloudEvent guide](11-creating-a-cloud-event.md)

  </TabItem>
</Tabs>

