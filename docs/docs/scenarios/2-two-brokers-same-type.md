---
sidebar_position: 2
---

# Two Brokers same Broker Type - Emit to external Broker

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import ThemeImage from '../../src/components/ThemeImage';

<ThemeImage scenario="2"></ThemeImage>

`App 1` has only connection to `Broker 1`, which is considered the `app` domain for this app. This is the same as
the [Single Broker](./1-single-broker.md) scenario.

`App 3` has only connection to `Broker 2`, which is considered the `app domain` for this app. This is the same as
the [Single Broker](./1-single-broker.md) scenario.

`App 2` has two brokers, `Broker 1` and `Broker 2`. `Broker 1` is considered the `app` domain for this app, and `Broker 2`
is considered an external broker which will be called `accounts` domain for this scenario.

So you need to configure the connection properties for each broker.

In this scenario, `Broker 1` is considered the `app` domain by default, so `App 2` can listen and send all operations
from/to this broker, but it can only publish commands and queries to `Broker 2` and listen for events from the
`Broker 2` (`accounts` domain).

Note: `App 2` cannot listen notification events, cannot listen for queries, and commands from `Broker 2`. This is for
responsibility segregation.

To send commands and queries to `Broker 2` you need to use the `DirectAsyncGateway` interface with the `accounts` domain
, and the same when listen, you should pass the domain name.

### Listen from external domain

```java
@Bean
@Primary
public HandlerRegistry handlerRegistrySubs(UseCase useCase) {
    return HandlerRegistry.register()
            //.serveQuery(...)
            //.handleCommand(...)
            //.listenEvent(...)
            //.listenNotificationEvent(...)
            .listenDomainEvent("accounts", "event-name", handler::process, MyEventData.class)
            .listenDomainCloudEvent("accounts", "event-name", handler::processCloudEvent);
}
```

### Send to external domain

```java
@Service
@RequiredArgsConstructor
public class SampleRestController {
    private final DirectAsyncGateway directAsyncGateway;

    public Mono<Teams> getTeams() {
        AsyncQuery<Request> query = ....
        return directAsyncGateway.requestReply(query, "external-service", Teams.class, "accounts");
    }

    public Mono<Teams> getTeamsCloudEvent() {
        CloudEvent query = ....
        return directAsyncGateway.requestReply(query, "external-service", CloudEvent.class, "accounts")
                .map(...);
    }
}
```


Next are configurations needed to set up this scenario for `App 2`.

<Tabs>
  <TabItem value="rabbitmq" label="RabbitMQ" default>

`App 2` needs two domains: `app` for `Broker 1` and `accounts` for `Broker 2`, each with its own connection properties.
See [RabbitMQ Configuration](../reactive-commons/configuration_properties/1-rabbitmq.md) for the full list of
properties, and
the [Approach 2](../reactive-commons/configuration_properties/1-rabbitmq.md#approach-2-hybrid-yaml--rabbitpropscustomizer)
section for loading connection credentials from a secrets manager instead of YAML.

```yaml title="application.yaml"
app:
  async:
    app: # this is the name of the default domain, connected to Broker 1
      connectionProperties:
        host: localhost
        port: 5672
        username: guest
        password: guest
        virtual-host: /
    # Another domain can be configured with the same properties structure as app
    accounts: # this is a second domain name, connected to Broker 2
      connectionProperties:
        host: localhost
        port: 5673
        username: guest
        password: guest
        virtual-host: /accounts
```

  </TabItem>
  <TabItem value="kafka" label="Kafka">

`App 2` needs two domains: `app` for `Broker 1` and `accounts` for `Broker 2`, each with its own connection properties.
See [Kafka Configuration](../reactive-commons/configuration_properties/2-kafka.md) for the full list of properties, and
the [Approach 2](../reactive-commons/configuration_properties/2-kafka.md#approach-2-hybrid-yaml--kafkapropscustomizer)
section for loading connection credentials from a secrets manager instead of YAML.

```yaml title="application.yaml"
reactive:
  commons:
    kafka:
      app: # this is the name of the default domain, connected to Broker 1
        connectionProperties:
          bootstrap-servers: localhost:9092
      # Another domain can be configured with the same properties structure as app
      accounts: # this is a second domain name, connected to Broker 2
        connectionProperties:
          bootstrap-servers: localhost:9093
```

  </TabItem>
</Tabs>