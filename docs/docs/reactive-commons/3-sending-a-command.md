---
sidebar_position: 3
---

# Sending a Command

:::info only available in RabbitMQ
:::

## API specification

### Command model

To send a Command we need to know the Command structure, which is represented with the next class:

```java
public class Command<T> {
    private final String name;
    private final String commandId;
    private final T data;
}
```

Where name is the command name, commandId is an unique command identifier and data is a JSON Serializable payload.

### DirectAsyncGateway interface

```java
public interface DirectAsyncGateway {

    <T> Mono<Void> sendCommand(Command<T> command, String targetName); 

    <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis);    

    <T> Mono<Void> sendCommand(Command<T> command, String targetName, String domain); // Send to specific domain

    <T> Mono<Void> sendCommand(Command<T> command, String targetName, long delayMillis, String domain); // Send to specific domain with delay

    Mono<Void> sendCommand(CloudEvent command, String targetName); // Send with CloudEvent format

    Mono<Void> sendCommand(CloudEvent command, String targetName, long delayMillis); // Send with CloudEvent format and delay

    Mono<Void> sendCommand(CloudEvent command, String targetName, String domain); // Send with CloudEvent format to specific domain
    
    Mono<Void> sendCommand(CloudEvent command, String targetName, long delayMillis, String domain);
}
```

You can send a `CloudEvent` or a `Command\<T>` to a target application. You also can send a command to a specific domain
(remote broker out of you application context).

## Enabling autoconfiguration

To send Commands you should enable the respecting spring boot autoconfiguration using the `@EnableDirectAsyncGateway` annotation
For example:

```java
@RequiredArgsConstructor
@EnableDirectAsyncGateway
public class ReactiveDirectAsyncGateway {
    public static final String TARGET_NAME = "other-app";// refers to remote spring.application.name property
    public static final String SOME_COMMAND_NAME = "some.command.name";
    private final DirectAsyncGateway gateway; // Auto injected bean created by the @EnableDirectAsyncGateway annotation

    public Mono<Void> runRemoteJob(Object command/*change for proper model*/)  {
         return gateway.sendCommand(new Command<>(SOME_COMMAND_NAME, UUID.randomUUID().toString(), command), TARGET_NAME);
    }
}
```

After that you can send commands from you application to a remote application that handles this command.

## Sending a Raw Command

There is no separate API to *send* a raw command: `RawCommandHandler` (see
[Listening Raw Commands](./7-handling-commands.md#listening-raw-commands)) is a **receiving-side** concept only. On the
sending side you always call `sendCommand(Command<T>, targetName)` or `sendCommand(CloudEvent, targetName)`, exactly as
in [Enabling autoconfiguration](#enabling-autoconfiguration) above. What makes a command "raw" is entirely decided by
the **receiver**: it processes the command without converting it to a `Command<T>` or `CloudEvent` first, and without
filtering by command name.

The example below sends one command the normal way, and shows the two ways the target application could receive it,
to make that distinction concrete.

```java title="Sender: no different from any other command"
@RequiredArgsConstructor
@EnableDirectAsyncGateway
public class ReactiveDirectAsyncGateway {
    public static final String TARGET_NAME = "other-app"; // remote spring.application.name
    public static final String SOME_COMMAND_NAME = "some.command.name";
    private final DirectAsyncGateway gateway;

    public Mono<Void> runRemoteJob(Object payload) {
        return gateway.sendCommand(new Command<>(SOME_COMMAND_NAME, UUID.randomUUID().toString(), payload), TARGET_NAME);
    }
}
```

```java title="Receiver option A: typed handler, filtered by command name"

@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(CommandsHandler commands) {
        return HandlerRegistry.register()
                .handleCommand("some.command.name", commands::handleCommandA, Payload.class);
    }
}
```

```java title="Receiver option B: raw handler, receives every command regardless of its name"

@Configuration
public class HandlerRegistryConfiguration {

    @Bean
    public HandlerRegistry handlerRegistry(CommandsHandler commands) {
        return HandlerRegistry.register()
                .handleRawCommand(commands::handleRawCommandA);
    }
}
```

Both options receive the very same message the sender published; the difference is entirely in how the **target
application** chose to register its handler. A `RawCommandHandler` receives it as a `RawMessage`, cast to
`RabbitMessage` to reach the body, headers and other broker-level properties, as shown in
[Listening Raw Commands](./7-handling-commands.md#listening-raw-commands).

This is only relevant for RabbitMQ, since commands are not supported on Kafka at all. A raw handler is useful for
consumers that do not want to declare a handler per command name, or that need the raw body/headers rather than a
deserialized payload — for instance a generic audit log, or a gateway that forwards commands elsewhere unopened.

## Example

You can see a real example at [samples/async/async-sender-client](https://github.com/reactive-commons/reactive-commons-java/tree/master/samples/async/async-sender-client)