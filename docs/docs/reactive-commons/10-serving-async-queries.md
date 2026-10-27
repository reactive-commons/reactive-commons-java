---
sidebar_position: 10
---

# Serving Async Queries

:::info Only available in RabbitMQ
:::

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

### Delegated queries

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

## Queries whose caller stopped waiting

The caller of a query waits for its reply up to its reply timeout (15 seconds by default). The gateway publishes the
moment it stops waiting with the query, in the `x-reply-deadline-millis` header. Queries published by versions without
that header carry an AMQP `timestamp`, which is accurate to one second only, and the `x-reply-timeout-millis` header;
the deadline is then computed from both, so it can be up to a second early.

The gateway also publishes the query with its reply timeout as its AMQP `expiration`, so the broker never delivers a
query that waited in its queue longer than its caller waits for the reply: it drops it. With `withDLQRetry: true` the
queue dead-letters it to the retry queue instead, and the broker drops it when it comes back, as a dead-letter cycle
without any rejection. This depends neither on the clocks of the hosts nor on `discardTimeoutQueries`. The deadline
matters for the queries already delivered to the listener when their caller gives up (e.g. waiting for a busy handler,
up to `prefetchCount` of them) and for the queries published by versions without the expiration.

What the listener does with that deadline depends on `app.async.<domain>.direct.discardTimeoutQueries`:

| Situation                                      | `discardTimeoutQueries: false` (default)                                 | `discardTimeoutQueries: true`                                                                                 |
|------------------------------------------------|--------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| The query arrives after the deadline           | The handler runs and its reply is published, although nobody receives it | Discarded without running the handler (`reactive_commons.consumer.query.discarded{reason=timeout}`)          |
| The deadline is reached while the handler runs | The handler runs until it completes                                      | The handler is cancelled and no reply is published; the query is discarded as above                          |
| The handler fails after the deadline           | Discarded instead of retried                                             | Discarded instead of retried                                                                                  |
| The handler fails before the deadline          | Retried according to the retry strategy                                  | Retried according to the retry strategy                                                                       |

With `false`, the queries already delivered to a slow listener, and a backlog of queries published by older versions
(e.g. after the listener was down), are fully handled, even those whose callers already gave up, while the new ones
wait behind them; and the callers that retry after their timeout make each query run several times.

:::tip[Recommended: `discardTimeoutQueries: true`]
Enable it when:

- the query handlers only read data (a cancelled handler must not leave a change half done);
- the clocks of the hosts are synchronized (e.g. with NTP): the deadline is set with the clock of the caller and
  checked with the clock of the listener;
- the callers use a version that publishes `x-reply-deadline-millis`, especially with reply timeouts of one or two
  seconds.
:::

```yaml title="application.yaml"
app:
  async:
    app:
      direct:
        discardTimeoutQueries: true
```

## Example

You can see a real example at [samples/async/async-receiver-responder](https://github.com/reactive-commons/reactive-commons-java/tree/master/samples/async/async-receiver-responder)
