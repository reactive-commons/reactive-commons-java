---
sidebar_position: 9
---

# Serving Async Queries

## HandlerRegistry configuration

To serve an async query you should register it in the HandlerRegistry and make it available as a Bean

### Listening Async Queries

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(QueriesHandler queries) {
        return HandlerRegistry.register()
                .serveQuery("some.query.name", queries::handleQueryA, Object.class/*change for proper model*/);
    }
}
```

To effectively start listening for queries you should add the annotation `@EnableQueryListeners` to your MainApplication class or any other spring Configuration class, for example the `QueriesHandler` class can be like:

```java
@EnableQueryListeners
public class QueriesHandler {
    public Mono<Object/*change for proper model*/> handleQueryA(Object query/*change for proper model*/) {
        System.out.println("query received->" + query);
        return Mono.just("Response Data");
    }
}
```

As the model of queries is direct, a consumer always can send queries to the service provider, by this reason you may receive queries that you don't have configured.

### Wildcards

You may need to handle variable queries names that have the same structure, in that case you can specfy a pattern with '*' wildcard, for example:

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(QueriesHandler queries) {
        return HandlerRegistry.register()
                .serveQuery("*.some.query", queries::handleQueryA, Object.class/*change for proper model*/);
    }
}
```

So any consumer can send a query with a name that matches with pattern, for example: `my.some.query`

### Delegated Queries

There is a concept introduced in queries that cannot be resolved locally and may require a later answer, in that case we are in front of the next scenario:

- A consumer application called APP1 make an async query to an application called APP2 (with horizontal scaling).
- an instance A of the APP2 receives the query and stores a reference to respond later.
- When response is fulfilled, an external source makes an HTTPS Call to an endpoint of an instance of APP2 but it can be diferent to the instance A, for example can be the instance B.
- The instance B of APP2 queries for the saved reference and uses DirectAsyncGateway to answer the pending query to the application APP1.

This scenario can be resolved with ReactiveCommons by using the next resources:

Enable the query listener and handler

```java
@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(QueriesHandler queries) {
        return HandlerRegistry.register()
                .serveQuery("delegated.query", queries::delegatedQuery, Object.class/*change for proper model*/);
    }
}
```

```java
@EnableQueryListeners
public class QueriesHandler {

    public Mono<Void> delegatedQuery(From from, Object query/*change for proper model*/) {
        System.out.println("query received->" + query); // TODO: Remove this line
        return saveReference(from).then(fireAsyncOperation(from.getCorrelationID(), query));
    }

    private Mono<Void> fireAsyncOperation(String correlationId, Object query) {
        // do async operation sending the correlationId to correlate future external reply
        return Mono.empty();
    }

    private Mono<Void> saveReference(From from) {
        // save reference using the attribute correlationId as key
        return Mono.empty();
    }
}
```

When some external source notifies our APP2 instance with the answer we should find the saved reference and reply using the DirectAsyncGateway API like:

```java
@RequiredArgsConstructor
@EnableDirectAsyncGateway
public class ReactiveDirectAsyncGateway {
    private final DirectAsyncGateway gateway; // Auto injected bean created by the @EnableDirectAsyncGateway annotation

    public  Mono<Void> replyDelegate(String correlationId, Object response/*change for proper model*/)  {
        return getReference(correlationId)
                .flatMap(from -> gateway.reply(response, from));
    }
    
    public Mono<From> getReference(String correlationId) {
        // do respective query to get the From object by correlationId key
        return Mono.empty();
    }
}
```

With these steps the delegated query can be done!

## Example

You can see a real example at [samples/async/async-receiver-responder](https://github.com/reactive-commons/reactive-commons-java/tree/master/samples/async/async-receiver-responder)