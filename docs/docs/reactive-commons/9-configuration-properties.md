---
sidebar_position: 8
---

# Configuration Properties

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

<Tabs>
  <TabItem value="rabbitmq" label="RabbitMQ" default>

You can customize some predefined variables of Reactive Commons

This can be done by Spring Boot `application.yaml` or by overriding
the [AsyncProps](https://github.com/reactive-commons/reactive-commons-java/blob/master/starters/async-rabbit-starter/src/main/java/org/reactivecommons/async/rabbit/config/props/AsyncProps.java)
bean.

```yaml
app:
  async:
    app: # this is the name of the default domain
      withDLQRetry: false # if you want to have dlq queues with retries you can set it to true, you cannot change it after queues are created, because you will get an error, so you should delete topology before the change.
      maxRetries: -1 # -1 will be considered default value. When withDLQRetry is true, it will be retried 10 times. When withDLQRetry is false, it will be retried indefinitely.
      retryDelay: 1000 # interval for message retries, with and without DLQRetry
      listenReplies: true # if you will not use ReqReply patter you can set it to false
      createTopology: true # if your organization have restrictions with automatic topology creation you can set it to false and create it manually or by your organization process.
      delayedCommands: false # Enable to send a delayed command to an external target
      prefetchCount: 250 # is the maximum number of in flight messages you can reduce it to process less concurrent messages, this settings acts per instance of your service
      useDiscardNotifierPerDomain: false # if true it uses a discard notifier for each domain,when false it uses a single discard notifier for all domains with default 'app' domain
      enabled: true # if you want to disable this domain you can set it to false
      mandatory: false # if you want to enable mandatory messages, you can set it to true, this will throw an exception if the message cannot be routed to any queue
      brokerType: "rabbitmq" # please don't change this value
      flux:
        maxConcurrency: 250 # max concurrency of listener flow
      domain:
        ignoreThisListener: false # Allows you to disable event listener for this specific domain
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
      connectionProperties: # you can override the connection properties of each domain
        host: localhost
        port: 5672
        username: guest
        password: guest
        virtual-host: /
    # Another domain can be configured with same properties structure that app
    accounts: # this is a second domain name and can have another independent setup
      connectionProperties: # you can override the connection properties of each domain
        host: localhost
        port: 5672
        username: guest
        password: guest
        virtual-host: /accounts
```

You can override this settings programmatically through a `AsyncRabbitPropsDomainProperties` bean.

```java
package sample;

import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncRabbitPropsDomainProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@Configuration
public class MyDomainConfig {

    @Bean
    @Primary
    public AsyncRabbitPropsDomainProperties customDomainProperties() {
        RabbitProperties propertiesApp = new RabbitProperties();
        propertiesApp.setHost("localhost");
        propertiesApp.setPort(5672);
        propertiesApp.setVirtualHost("/");
        propertiesApp.setUsername("guest");
        propertiesApp.setPassword("guest");

        RabbitProperties propertiesAccounts = new RabbitProperties();
        propertiesAccounts.setHost("localhost");
        propertiesAccounts.setPort(5672);
        propertiesAccounts.setVirtualHost("/accounts");
        propertiesAccounts.setUsername("guest");
        propertiesAccounts.setPassword("guest");

        return AsyncRabbitPropsDomainProperties.builder()
                .withDomain("app", AsyncProps.builder()
                        .connectionProperties(propertiesApp)
                        .build())
                .withDomain("accounts", AsyncProps.builder()
                        .connectionProperties(propertiesAccounts)
                        .build())
                .build();
    }
}
```

## Loading properties from a secret

Additionally, if you want to set only connection properties you can use the `AsyncPropsDomain.RabbitSecretFiller` class.

```java

@Bean
public AsyncPropsDomain.RabbitSecretFiller customFiller() {
    return (domain, asyncProps) -> {
        // customize asyncProps here by domain
    };
}
```

For example if you use the [Secrets Manager](https://github.com/bancolombia/secrets-manager) project, you may use
the next code to load the properties from a secret:

1. Create a class with the properties that you will load from the secret:

```java
import lombok.Builder;
import lombok.Data;
import org.reactivecommons.async.rabbit.config.RabbitProperties;

@Data
@Builder
public class RabbitConnectionProperties {
    private String hostname;
    private String password;
    private String username;
    private Integer port;
    private String virtualhost;
    private boolean ssl;

    public RabbitProperties toRabbitProperties() {
        var rabbitProperties = new RabbitProperties();
        rabbitProperties.setHost(this.hostname);
        rabbitProperties.setUsername(this.username);
        rabbitProperties.setPassword(this.password);
        rabbitProperties.setPort(this.port);
        rabbitProperties.setVirtualHost(this.virtualhost);
        rabbitProperties.getSsl().setEnabled(this.ssl); // To enable SSL
        return rabbitProperties;
    }
}
```

2. Use the `SecretsManager` to load the properties from the secret:

```java
import co.com.bancolombia.secretsmanager.api.GenericManager;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.GenericArrayType;

@Log4j2
@Configuration
public class AsyncEventBusConfig {

    // TODO: You should create the GenericManager bean as indicated in Secrets Manager library
    @Bean
    public AsyncPropsDomain.RabbitSecretFiller rabbitSecretFiller(GenericManager genericManager) {
        return (domain, props) -> {
            if (props.getSecret() != null) {
                log.info("Loading RabbitMQ connection properties from secret: {}", props.getSecret());
                props.setConnectionProperties(getFromSecret(genericManager, props.getSecret()));
                log.info("RabbitMQ connection properties loaded successfully with host: '{}'",
                        props.getConnectionProperties().getHost());
            }
        };
    }

    @SneakyThrows
    private RabbitProperties getFromSecret(GenericManager genericManager, String secretName) {
        return genericManager.getSecret(secretName, RabbitConnectionProperties.class).toRabbitProperties();
    }
}

```

  </TabItem>
  <TabItem value="kafka" label="Kafka">
    You can customize some predefined variables of Reactive Commons

This can be done by Spring Boot `application.yaml` or by overriding
the [AsyncKafkaProps](https://github.com/reactive-commons/reactive-commons-java/blob/master/starters/async-kafka-starter/src/main/java/org/reactivecommons/async/kafka/config/props/AsyncKafkaProps.java)
bean.

```yaml
reactive:
  commons:
    kafka:
      app: # this is the name of the default domain
        withDLQRetry: false # if you want to have dlq queues with retries you can set it to true, you cannot change it after queues are created, because you will get an error, so you should delete topology before the change.
        maxRetries: -1 # -1 will be considered default value. When withDLQRetry is true, it will be retried 10 times. When withDLQRetry is false, it will be retried indefinitely.
        retryDelay: 1000 # interval for message retries, with and without DLQRetry
        checkExistingTopics: true # if you don't want to verify topic existence before send a record you can set it to false
        createTopology: true # if your organization have restrictions with automatic topology creation you can set it to false and create it manually or by your organization process.
        useDiscardNotifierPerDomain: false # if true it uses a discard notifier for each domain,when false it uses a single discard notifier for all domains with default 'app' domain
        enabled: true # if you want to disable this domain you can set it to false
        brokerType: "kafka" # please don't change this value
        domain:
          ignoreThisListener: false # Allows you to disable event listener for this specific domain
        connectionProperties: # you can override the connection properties of each domain
          bootstrap-servers: localhost:9092
      # Another domain can be configured with same properties structure that app
      accounts: # this is a second domain name and can have another independent setup
        connectionProperties: # you can override the connection properties of each domain
          bootstrap-servers: localhost:9093
```

You can override this settings programmatically through a `AsyncKafkaPropsDomainProperties` bean.

```java
package sample;

import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.config.props.AsyncProps;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomainProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@Configuration
public class MyDomainConfig {

    @Bean
    @Primary
    public AsyncKafkaPropsDomainProperties customKafkaDomainProperties() {
        KafkaProperties propertiesApp = new KafkaProperties();
        propertiesApp.setBootstrapServers(List.of("localhost:9092"));

        KafkaProperties propertiesAccounts = new KafkaProperties();
        propertiesAccounts.setBootstrapServers(List.of("localhost:9093"));

        return AsyncKafkaPropsDomainProperties.builder()
                .withDomain("app", AsyncProps.builder()
                        .connectionProperties(propertiesApp)
                        .build())
                .withDomain("accounts", AsyncProps.builder()
                        .connectionProperties(propertiesAccounts)
                        .build())
                .build();
    }
}
```

Additionally, if you want to set only connection properties you can use the `AsyncKafkaPropsDomain.KafkaSecretFiller`
class.

```java

@Bean
@Primary
public AsyncKafkaPropsDomain.KafkaSecretFiller customKafkaFiller() {
    return (domain, asyncProps) -> {
        // customize asyncProps here by domain
    };
}
```

  </TabItem>
</Tabs>

## Propiedad mandatoy en RabbitMQ
La propiedad mandatory es un parámetro de publicación de mensajes en RabbitMQ que determina el comportamiento cuando un
mensaje no puede ser enrutado a ninguna cola. Esto puede ocurrir si no hay colas enlazadas al exchange o si la clave de
enrutamiento no coincide con ninguna de las colas disponibles.

Por defecto, esta opción está desactivada, pero si se activa (`mandatory = true`) funciona justo después de que el
mensaje es publicado en un exchange, pero antes de que se enrute a una cola.

Cuando se publica un mensaje con `mandatory = true`, RabbitMQ intentará enrutarlo desde el exchange hacia una o más
colas. Si ninguna cola recibe el mensaje, entonces:

- El mensaje no se pierde, pero no se entrega a ninguna cola.
- RabbitMQ activa un evento basic.return en el canal del productor.
- El productor debe tener un ReturnListener o un manejador equivalente para recibir y procesar el mensaje devuelto. Si
  no se define uno el mensaje se pierde.

#### Ejemplo

Suponiendo que tenemos:

- Un exchange tipo direct.
- Una cola enlazada con la clave `orden.creada`.
- Se publica un mensaje con clave `orden.cancelada` y `mandatory = true`.

Resultado:

- Si no hay ninguna cola enlazada con `orden.cancelada`, el mensaje no se enruta.
- Como `mandatory = true`, RabbitMQ intenta devolverlo al productor.
- Si se tiene un ReturnListener, se puede capturar y manejar ese mensaje, por ejemplo enviarlo a una cola de otro
  consumidor, colas DLQ, guardarlo en un archivo logs o en una base de datos.

### Ventajas

- Detección temprana de errores de enrutamiento: Evita que mensajes críticos “desaparezcan” sin rastro lo que facilita
  la identificación de configuraciones erróneas en bindings o patrones.
- Integridad y fiabilidad: Garantiza que cada mensaje encuentre un consumidor o, en su defecto, regrese al productor
  para un manejo alternativo (colas DLQ, logs, base de datos).
- Visibilidad operacional: Facilita métricas de “mensajes no enrutados” y alertas cuando el flujo de eventos no cumple
  las rutas previstas.

### Consideraciones

Aunque esta propiedad no evita problemas de rendimiento o degradación del clúster RabbitMQ, sí es útil para evitar la
pérdida de mensajes no enrutados y para detectar errores de configuración en el enrutamiento.

Cuando mandatory está activo, en condiciones normales (todas las rutas existen) no hay prácticamente impacto. En
situaciones anómalas, habrá un tráfico adicional de retorno por cada mensaje no enrutable. Esto supone carga extra
tanto para RabbitMQ (que debe enviar de vuelta el mensaje al productor) como para la aplicación emisora (que debe
procesar el mensaje devuelto).

### Implementación

Para habilitar la propiedad `mandatory` en Reactive Commons, puedes configurarla en el archivo `application.yaml` de tu
proyecto:

```yaml
app:
  async:
    app: # this is the name of the default domain
      mandatory: true # enable mandatory property
```

Ahora configuramos el handler de retorno para manejar los mensajes que no pudieron ser entregados correctamente, por
defecto estos mensajes se muestran en un log.
Para personalizar este comportamiento se crea una clase que implemente la interfaz `UnroutableMessageHandler` y se
registra como un bean de Spring:

```java
package sample;

import co.com.mypackage.usecase.MyUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.rabbit.communications.MyOutboundMessage;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageHandler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessageResult;

import java.nio.charset.StandardCharsets;

@Log
@Component
@RequiredArgsConstructor
public class ResendUnroutableMessageHandler implements UnroutableMessageHandler {

    private final MyUseCase useCase;

    @Override
    public Mono<Void> processMessage(OutboundMessageResult<MyOutboundMessage> result) {
        var returned = result.getOutboundMessage();
        log.severe("Unroutable message: exchange=" + returned.getExchange()
                + ", routingKey=" + returned.getRoutingKey()
                + ", body=" + new String(returned.getBody(), StandardCharsets.UTF_8)
                + ", properties=" + returned.getProperties()
        );
        
        // Process the unroutable message
        return useCase.sendMessage(new String(returned.getBody(), StandardCharsets.UTF_8));
    }
}
```

#### Enviar mensajes no enrutados a una cola

Para enviar el mensaje no enrutado a una cola, utilizamos las anotaciones `@EnableDomainEventBus` para 
[eventos de dominio](/reactive-commons-java/docs/reactive-commons/sending-a-domain-event), y `@EnableDirectAsyncGateway` 
para [comandos](/reactive-commons-java/docs/reactive-commons/sending-a-command) y 
[consultas asíncronas](/reactive-commons-java/docs/reactive-commons/making-an-async-query), según corresponda.

Es importante asegurarse de que la cola exista antes de enviar el mensaje, ya que, de lo contrario, este se perderá. 
Por lo tanto, se recomienda verificar o crear la cola previamente para garantizar una entrega exitosa.

```java
package sample;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.rabbit.communications.MyOutboundMessage;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.OutboundMessageResult;

@Component
@EnableDirectAsyncGateway
public class ResendUnroutableMessageHandler implements UnroutableMessageHandler {

    private final ObjectMapper objectMapper;
    private final String retryQueueName;
    private final DirectAsyncGateway gateway;

    public ResendUnroutableMessageHandler(
            ObjectMapper objectMapper,
            DirectAsyncGateway gateway,
            @Value("${adapters.rabbitmq.retry-queue-name}") String retryQueueName) {
        this.objectMapper = objectMapper;
        this.retryQueueName = retryQueueName;
        this.gateway = gateway;
    }

    public Mono<Void> emitCommand(String name, String commandId, Object data) {
        return Mono.from(gateway.sendCommand(
                // Connection with broker using the properties defined through the
                // AsyncRabbitPropsDomainProperties bean with the "logs" domain
                new Command<>(name, commandId, data), retryQueueName, "logs")
        );
    }

    @Override
    public Mono<Void> processMessage(OutboundMessageResult<MyOutboundMessage> result) {
        OutboundMessage returned = result.getOutboundMessage();
        try {
            // The unroutable message is a command, so the message body is deserialized to the Command class.
            // Use the DomainEvent class for domain events and the AsyncQuery class for asynchronous queries.
            Command<JsonNode> command = objectMapper.readValue(returned.getBody(), new TypeReference<>() {
            });
            
            // Send the message to the queue
            return emitCommand(command.getName(), command.getCommandId(), command.getData())
                    .doOnError(e -> log.severe("Failed to send the returned message: " + e.getMessage()));
        } catch (Exception e) {
            log.severe("Error deserializing the returned message: " + e.getMessage());
            return Mono.empty();
        }
    }
}
```

En la clase de configuración de RabbitMQ creamos el bean `UnroutableMessageProcessor` para registrar el handler de mensajes no enrutados.

```java
package sample;

import org.reactivecommons.async.rabbit.communications.UnroutableMessageNotifier;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageProcessor;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncRabbitPropsDomainProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration("rabbitMQConfiguration")
public class RabbitMQConfig {

    private final RabbitMQConnectionProperties properties;
    private final RabbitMQConnectionProperties propertiesLogs;
    private final Boolean withDLQRetry;
    private final Integer maxRetries;
    private final Integer retryDelay;

    public RabbitMQConfig(@Qualifier("rabbit") RabbitMQConnectionProperties properties,
                          @Qualifier("rabbitLogs") RabbitMQConnectionProperties propertiesLogs,
                          @Value("${adapters.rabbitmq.withDLQRetry}") Boolean withDLQRetry,
                          @Value("${adapters.rabbitmq.maxRetries}") Integer maxRetries,
                          @Value("${adapters.rabbitmq.retryDelay}") Integer retryDelay) {
        this.properties = properties;
        this.propertiesLogs = propertiesLogs;
        this.withDLQRetry = withDLQRetry;
        this.maxRetries = maxRetries;
        this.retryDelay = retryDelay;
    }

    // This bean is used to create the RabbitMQ connection properties for the application
    @Bean
    @Primary
    public AsyncRabbitPropsDomainProperties customDomainProperties() {
        var propertiesApp = new RabbitProperties();
        propertiesApp.setHost(properties.hostname());
        propertiesApp.setPort(properties.port());
        propertiesApp.setVirtualHost(properties.virtualhost());
        propertiesApp.setUsername(properties.username());
        propertiesApp.setPassword(properties.password());
        propertiesApp.getSsl().setEnabled(properties.ssl());

        var propertiesLogs = new RabbitProperties();
        propertiesLogs.setHost(propertiesDual.hostname());
        propertiesLogs.setPort(propertiesDual.port());
        propertiesLogs.setVirtualHost(propertiesDual.virtualhost());
        propertiesLogs.setUsername(propertiesDual.username());
        propertiesLogs.setPassword(propertiesDual.password());
        propertiesLogs.getSsl().setEnabled(propertiesDual.ssl());

        return AsyncRabbitPropsDomainProperties.builder()
                .withDomain("app", AsyncProps.builder()
                        .connectionProperties(propertiesApp)
                        .withDLQRetry(withDLQRetry)
                        .maxRetries(maxRetries)
                        .retryDelay(retryDelay)
                        .mandatory(Boolean.TRUE)
                        .build())
                .withDomain("logs", AsyncProps.builder()
                        .connectionProperties(propertiesLogs)
                        .mandatory(Boolean.TRUE)
                        .build())
                .build();
    }

    // This bean is used to register the handler for unroutable messages
    @Bean
    UnroutableMessageProcessor registerUnroutableMessageHandler(UnroutableMessageNotifier unroutableMessageNotifier,
                                                                ResendUnroutableMessageHandler handler) {
      var factory = new UnroutableMessageProcessor();
      unroutableMessageNotifier.listenToUnroutableMessages(handler);
      return factory;
    }

}
```
