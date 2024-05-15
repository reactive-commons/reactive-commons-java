---
sidebar_position: 1
---

# Getting Started

This quick start tutorial sets up a single node RabbitMQ and runs the sample reactive sender and consumer using Reactive Commons.

## Requirements

You need Java JRE installed (Java 17 or later).

You also need to install RabbitMQ. Follow the [instructions from the website](https://www.rabbitmq.com/download.html)

## Start RabbitMQ

Start RabbitMQ on your local machine with all the defaults (e.g. AMQP port is 5672).

### Contenerized

You can run it with Docker or Podman

```shell
podman run -d -p 5672:5672 -p 15672:15672 --name rabbitmq rabbitmq:management-alpine
```

## Spring Boot Application

The Spring Boot sample publishes and consumes messages with the `DomainEventBus`. This application illustrates how to configure Reactive Commons using RabbitMQ in a Spring Boot environment.

To build your own application using the Reactive Commons API, you need to include a dependency to Reactive Commons.

### Current version
![Maven metadata URL](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Forg%2Freactivecommons%2Fasync-commons-rabbit-starter%2Fmaven-metadata.xml)

### Dependency

```groovy
dependencies {
  implementation "org.reactivecommons:async-commons-rabbit-starter:<version>"
}
```
### Configuration properties

Also you need to include the name for your app in the `application.properties`, it is important because this value will be used
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

You can set the RabbitMQ connection properties through spring boot with the [`spring.rabbitmq.*` properties](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html)

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
    public RabbitProperties customRabbitProperties(){
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