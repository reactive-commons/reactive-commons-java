---
sidebar_position: 5
---

# Making an Async Query

:::info Only available in RabbitMQ
:::

## API specification

### AsyncQuery model

To send an async query we need to know the AsyncQuery structure, which is represented with the next class:

```java
public class AsyncQuery<T> {
    private final String resource;
    private final T queryData;
}
```

Where resource is the query name and queryData is a JSON Serializable payload.

### DirectAsyncGateway interface

This abstraction uses the same interface that Commands, but specific methods

```java
public interface DirectAsyncGateway {

    <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type);

    <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type, String domain); // Query to specific domain

    <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type); // Query with CloudEvent format

    <R extends CloudEvent> Mono<R> requestReply(CloudEvent query, String targetName, Class<R> type, String domain); // Query with CloudEvent format to specific domain

    <T> Mono<Void> reply(T response, From from); // Reply to a query
}
```

In this method the `Class\<R>` called type is the return type of the query, represented by a JSON Serializable object

You can send a `CloudEvent` or an `AsyncQuery\<T>` to a target application. You also can send a query to a specific
domain
(remote broker out of you application context).

## Enabling autoconfiguration

To do an Async Query you should enable the respecting spring boot autoconfiguration using the `@EnableDirectAsyncGateway` annotation
For example:

```java
@RequiredArgsConstructor
@EnableDirectAsyncGateway
public class ReactiveDirectAsyncGateway {
    public static final String TARGET_NAME = "other-app";// refers to remote spring.application.name property
    public static final String SOME_QUERY_NAME = "some.query.name";
    private final DirectAsyncGateway gateway; // Auto injected bean created by the @EnableDirectAsyncGateway annotation

    public Mono<Object /*change for proper model*/> requestForRemoteData(Object query/*change for proper model*/)  {
        return gateway.requestReply(new AsyncQuery<>(SOME_QUERY_NAME, query), TARGET_NAME, Object.class/*change for proper model*/);
    }
}
```

After that you can do async queries from you application to a remote application that handles this command.

:::caution[Do not block while processing a reply]
The replies of an application arrive on its reply queue, whose deliveries the RabbitMQ client dispatches one after
another on a single thread, and the code that processes a reply (the operators after `requestReply`) runs on that
thread. Code that blocks or takes long there holds back the replies of every other query of the application. Move
such work off that thread with `publishOn`, e.g. `.publishOn(Schedulers.boundedElastic())` before a blocking call.
:::

## Example

You can see a real example at [samples/async/async-sender-client](https://github.com/reactive-commons/reactive-commons-java/tree/master/samples/async/async-sender-client)
