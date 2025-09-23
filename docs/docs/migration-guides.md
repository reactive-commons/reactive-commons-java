---
sidebar_position: 4
---

# Migration

## From 5.x.x to 6.x.x

### New Features

- **Connection customization:** You can now customize the RabbitMQ connection by defining a
  `ConnectionFactoryCustomizer` bean. For more details,
  see [Customizing the connection](/reactive-commons-java/docs/reactive-commons/configuration_properties/rabbitmq#customizing-the-connection).

```java title="Programmatic configuration"

@Bean
public ConnectionFactoryCustomizer connectionFactoryCustomizer() {
    return (connectionFactory, asyncProps) -> {
        connectionFactory.setExceptionHandler(new MyCustomExceptionHandler()); // Optional custom exception handler
        connectionFactory.setCredentialsProvider(new MyCustomCredentialsProvider()); // Optional custom credentials provider
        return connectionFactory;
    };
}
```

### Change notes

- The configuration property `listenReplies` for RabbitMQ now defaults to `null`. Previously, it was `true`, causing all
  applications to subscribe to a reply queue even when not needed.
- The domain `app` is now **required**. If not defined, the application will fail to start.

### Actions

- If your application uses the ReqReply pattern, you must explicitly set `app.async.app.listenReplies` to `true`.
  Otherwise, it should be `false` to avoid unnecessary resource usage:

```yaml title="application.yaml"
app:
  async:
    app:
      listenReplies: true # set to true if ReqReply is required, false if not
```

```java title="Programmatic configuration"
@Configuration
public class MyDomainConfig {

    @Bean
    @Primary
    public AsyncRabbitPropsDomainProperties customDomainProperties() {
        RabbitProperties propertiesApp = new RabbitProperties();
        // Additional connection configuration goes here...
        return AsyncRabbitPropsDomainProperties.builder()
                .withDomain("app", AsyncProps.builder()
                        .connectionProperties(propertiesApp)
                        .listenReplies(Boolean.TRUE) // set to true if ReqReply is required, false if not
                        .build())
                .build();
    }
}
```

---

- The domain `app` must be defined in your configuration. Otherwise, the application will throw an exception at startup:

```yaml title="application.yaml"
app:
  async:
    app: # Configure the 'app' domain
    # domain configuration goes here
```

```java title="Programmatic configuration"
@Configuration
public class MyDomainConfig {

    @Bean
    @Primary
    public AsyncRabbitPropsDomainProperties customDomainProperties() {
        RabbitProperties propertiesApp = new RabbitProperties();
        // Additional connection configuration goes here...
        return AsyncRabbitPropsDomainProperties.builder()
                .withDomain("app", AsyncProps.builder() // Configure the 'app' domain
                        .connectionProperties(propertiesApp)
                        .build())
                .build();
    }
}
```

## From 4.x.x to 5.x.x

### New Features

- **Support for multiple brokers:** It is now possible to configure and connect to up to two brokers simultaneously,
  using
  independent domains in the configuration.

### Change notes

- Configuration properties are now defined per domain, allowing each to have its own properties and connection
  settings.
- The broker connection is no longer manually defined in the code. It is now automatically managed based on the
  configuration declared in the `application.yaml` file or through programmatic configuration.

### Actions

- The `app` domain needs to be defined to specify the configuration properties.

Before:

```yaml title="application.yaml"
app:
  async:
    withDLQRetry: true
    maxRetries: 1
    retryDelay: 1000
```

Now:

```yaml title="application.yaml"
app:
  async:
    app: # this is the name of the default domain
      withDLQRetry: true
      maxRetries: 1
      retryDelay: 1000
```

- Migrate the connection configuration:

Before: the connection was defined manually in a Java class, as shown below:

```java
@Log4j2
@Configuration
@RequiredArgsConstructor
public class MyDomainConfig {

    private final RabbitMQConnectionProperties properties;
    private static final String TLS = "TLSv1.2";
    private static final String FAIL_MSG = "Error creating ConnectionFactoryProvider in enroll";

    @Primary
    @Bean
    public ConnectionFactoryProvider getConnectionFactoryProvider() {
        final var factory = new ConnectionFactory();
        var map = PropertyMapper.get();
        map.from(properties::hostname).whenNonNull().to(factory::setHost);
        map.from(properties::port).to(factory::setPort);
        map.from(properties::username).whenNonNull().to(factory::setUsername);
        map.from(properties::password).whenNonNull().to(factory::setPassword);
        map.from(properties::ssl).whenTrue().as(isSsl -> factory).to(this::configureSsl);
        return () -> factory;
    }

    private void configureSsl(ConnectionFactory factory) {
        try {
            var sslContext = SSLContext.getInstance(TLS);
            var trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
            factory.useSslProtocol(sslContext);
        } catch (KeyManagementException | KeyStoreException | NoSuchAlgorithmException e) {
            log.error("{}: {}", FAIL_MSG, e);
        }
    }
}
```

Now: the connection is configured directly in the `application.yaml` file per domain:

```yaml title="application.yaml"
app:
  async:
    app: # this is the name of the default domain
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

Domains can also be configured programmatically:

```java title="Programmatic configuration"
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