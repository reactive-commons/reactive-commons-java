---
sidebar_position: 4
---

# Making an Async Query

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
}
```

In this method the Class\<R> called type is the return type of the query, represented by a JSON Serializable object

## Enabling autoconfiguration

To do an Async Query you should enable the respecting spring boot autoconfiguration using the `@EnableDirectAsyncGateway` annotation
For example:

```java
@RequiredArgsConstructor
@EnableDirectAsyncGateway
public class ReactiveDirectAsyncGateway {
    public static final String TARGET_NAME = "other-app";// refers to remote spring.application.name property
    public static final String SOME_QUERY_NAME = "some.query.name";
    private final DirectAsyncGateway gateway; // Auto injectec bean created by the @EnableDirectAsyncGateway annotation

    public Mono<Object /*change for proper model*/> requestForRemoteData(Object query/*change for proper model*/)  {
        return gateway.requestReply(new AsyncQuery<>(SOME_QUERY_NAME, query), TARGET_NAME, Object.class/*change for proper model*/);
    }
}
```

After that you can do async queries from you application to a remote application that handles this command.

## Example

You can see a real example at [samples/async/async-sender-client](https://github.com/reactive-commons/reactive-commons-java/tree/master/samples/async/async-sender-client)