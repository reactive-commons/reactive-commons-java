---
sidebar_position: 1
---

# Getting Started

## What Changes in this Variant?

Before start this guide please review base [Reactive Commons](/reactive-commons-java/docs/category/reactive-commons)

### Multi Broker or Multi Domain support

This variant enables to you the ability to listen events from different domains, send commands to different domains and make queries to different domains.

### Cloud Events

This variant also includes the Cloud Events specification

## Setup

### Current version
![Maven metadata URL](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Forg%2Freactivecommons%2Fasync-commons-rabbit-starter-eda%2Fmaven-metadata.xml)

### Dependency

```groovy
dependencies {
  implementation "org.reactivecommons:async-commons-rabbit-starter-eda:<version>"
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