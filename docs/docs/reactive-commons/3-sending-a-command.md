---
sidebar_position: 2
---

# Sending a Command

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

    Mono<Void> sendCommand(CloudEvent command, String targetName, String domain); // Send with CloudEvent format to an specific domain
}
```

You can send a CloudEvent or a Command\<T> to a target application. You also can send a command to a specific domain
(remote broker out of you application context).

## Enabling autoconfiguration

To send Commands you should enable the respecting spring boot autoconfiguration using the `@EnableDomainEventBus` annotation
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

## Example

You can see a real example at [samples/async/async-sender-client](https://github.com/reactive-commons/reactive-commons-java/tree/master/samples/async/async-sender-client)