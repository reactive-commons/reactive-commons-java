---
sidebar_position: 11
---

# Creating a CloudEvent

## Additional Dependencies

To start using this approach you should know the base of `Events`, `Commands` and `AsyncQuery`

This variant includes an object mapper that allows to you to emit CloudEvent serialize and deserialize.

Each API includes overloads related to emit CloudEvent events, send CloudEvent commands and make CloudEvent async queries.

In order to instantiate a CloudEvent you may need to include the dependencies:

```groovy
implementation 'io.cloudevents:cloudevents-core:<version>'
```
## Creating a CloudEvent instance

```java
CloudEvent commandCloudEvent = CloudEventBuilder.v1()
    .withId(UUID.randomUUID().toString())
    .withSource(URI.create("https://reactivecommons.org/foos"))
    .withType("some.command.name")
    .withTime(OffsetDateTime.now())
    .withData("application/json", CloudEventBuilderExt.asCloudEventData(commandData)) // commandData is your own object
    .build();

CloudEvent queryCloudEvent = CloudEventBuilder.v1()
    .withId(UUID.randomUUID().toString())
    .withSource(URI.create("https://reactivecommons.org/foos"))
    .withType("some.query.name")
    .withTime(OffsetDateTime.now())
    .withData("application/json", CloudEventBuilderExt.asCloudEventData(queryData)) // queryData is your own object
    .build();

CloudEvent eventCloudEvent = CloudEventBuilder.v1()
    .withId(UUID.randomUUID().toString())
    .withSource(URI.create("https://reactivecommons.org/foos"))
    .withType("some.event.name")
    .withDataContentType("application/json")
    .withTime(OffsetDateTime.now())
    .withData("application/json", CloudEventBuilderExt.asCloudEventData(eventData)) // eventData is your own object
    .build();
```