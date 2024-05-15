---
sidebar_position: 8
---

# Configuration Properties

You can customize some predefined variables of Reactive Commons

This can be done by Spring Boot `application.yaml` or by overriding the [AsyncProps](https://github.com/reactive-commons/reactive-commons-java/blob/master/async/async-rabbit-starter/src/main/java/org/reactivecommons/async/rabbit/config/props/AsyncProps.java) bean.

```yaml
app:
  async:
    withDLQRetry: false # if you want to have dlq queues with retries you can set it to true, you cannot change it after queues are created, because you will get an error, so you should delete topology before the change.
    maxRetries: -1 # -1 will be considered default value. When withDLQRetry is true, it will be retried 10 times. When withDLQRetry is false, it will be retried indefinitely.
    retryDelay: 1000 # interval for message retries, with and without DLQRetry
    listenReplies: true # if you will not use ReqReply patter you can set it to false
    createTopology: true # if your organization have restricctions with automatic topology creation you can set it to false and create it manually or by your organization process.
    delayedCommands: false # Enable to send a delayed command to an external target
    prefetchCount: 250 # is the maximum number of in flight messages you can reduce it to process less concurrent messages, this settings acts per instance of your service
    flux:
        maxConcurrency: 250 # max concurrency of listener flow
    domain:
        events:
            exchange: domainEvents # you can change the exchange, but you should do it in all applications consistently
            eventsSuffix: subsEvents # events queue name suffix, name will be like ${spring.application.name}.${app.async.domain.events.eventsSuffix}
            notificationSuffix: notification # notification events queue name suffix
    direct:
        exchange: directMessages # you can change the exchange, but you should do it in all applications
        querySuffix: query # queries queue name suffix, name will be like ${spring.application.name}.${app.async.direct.querySuffix}
        commandSuffix: '' # commands queue name suffix, name will be like ${spring.application.name}.${app.async.direct.querySuffix} or ${spring.application.name} if empty by default
        discardTimeoutQueries: false # enable to discard this condition
    global:
        exchange: globalReply # you can change the exchange, but you should do it in all applications
        repliesSuffix: replies # async query replies events queue name suffix
```