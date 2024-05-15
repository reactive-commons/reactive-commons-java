---
sidebar_position: 2
---

# Creating a CloudEvent

## Aditional Dependencies

To start using this approach you should know the base of `Events`, `Commands` and `AsyncQuery`

This varian includes an object mapper that allows to you to emit CloudEvent serialize and deserialize.

Each API includes overloads related to emit CloudEvent events, send CloudEvent commands and make CloudEvent async queries.

In order to instantiate a CloudEvent you need to include the dependencies:

```groovy
implementation 'io.cloudevents:cloudevents-core:3.0.0'
implementation 'io.cloudevents:cloudevents-http-basic:3.0.0'
implementation 'io.cloudevents:cloudevents-json-jackson:3.0.0'
implementation 'io.cloudevents:cloudevents-spring:3.0.0'
```
## Creating a CloudEvent instance

```java
ObjectMapper om = new ObjectMapper();

CloudEvent commandCloudEvent = CloudEventBuilder.v1()
    .withId(UUID.randomUUID().toString())
    .withSource(URI.create("https://reactivecommons.org/foos"))
    .withType("some.command.name")
    .withTime(OffsetDateTime.now())
    .withData("application/json", om.writeValueAsBytes(command)) // command is your own object
    .build();

CloudEvent queryCloudEvent = CloudEventBuilder.v1()
    .withId(UUID.randomUUID().toString())
    .withSource(URI.create("https://reactivecommons.org/foos"))
    .withType("some.query.name")
    .withTime(OffsetDateTime.now())
    .withData("application/json", om.writeValueAsBytes(query)) // query is your own object
    .build();

CloudEvent eventCloudEvent = CloudEventBuilder.v1()
    .withId(UUID.randomUUID().toString())
    .withSource(URI.create("https://reactivecommons.org/foos"))
    .withType("some.event.name")
    .withDataContentType("application/json")
    .withTime(OffsetDateTime.now())
    .withData("application/json", om.writeValueAsBytes(event)) // event is your own object
    .build();
```