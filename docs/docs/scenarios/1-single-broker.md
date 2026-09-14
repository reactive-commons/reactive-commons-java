---
sidebar_position: 1
---

# Single Broker

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import ThemeImage from '../../src/components/ThemeImage';

<ThemeImage scenario="1"></ThemeImage>

Both apps the `App 1` and the `App 2` are connected to the same `Broker`, so both has the same connection configuration,
and `Broker` is considered the `app` domain for both apps.

<Tabs>
  <TabItem value="rabbitmq" label="RabbitMQ" default>

Both apps connect to the same broker, so both use the **same connection properties** for the `app` domain. See
[RabbitMQ Configuration](../reactive-commons/configuration_properties/1-rabbitmq.md) for the full list of properties,
YAML and programmatic configuration approaches.

```yaml title="application.yaml"
app:
  async:
    app: # this is the name of the default domain, shared by App 1 and App 2
      connectionProperties:
        host: localhost
        port: 5672
        username: guest
        password: guest
        virtual-host: /
```

  </TabItem>
  <TabItem value="kafka" label="Kafka">

Both apps connect to the same cluster, so both use the **same connection properties** for the `app` domain. See
[Kafka Configuration](../reactive-commons/configuration_properties/2-kafka.md) for the full list of properties, YAML
and programmatic configuration approaches.

```yaml title="application.yaml"
reactive:
  commons:
    kafka:
      app: # this is the name of the default domain, shared by App 1 and App 2
        connectionProperties:
          bootstrap-servers: localhost:9092
```

  </TabItem>
</Tabs>