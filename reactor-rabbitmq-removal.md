# Eliminación de reactor-rabbitmq: Justificación y Diseño

## Contexto

A partir de la versión **8.0.0** de Reactive Commons, se eliminó la dependencia `io.projectreactor.rabbitmq:reactor-rabbitmq:1.5.6` y se reemplazó con clases bridge propias que envuelven directamente `com.rabbitmq:amqp-client`.

Este documento explica las razones técnicas, las alternativas evaluadas y las consideraciones de diseño.

---

## ¿Por qué se eliminó reactor-rabbitmq?

### 1. Proyecto archivado y sin mantenimiento

El repositorio [`reactor/reactor-rabbitmq`](https://github.com/reactor/reactor-rabbitmq) fue **archivado en 2023**. Esto significa:

- No recibe correcciones de seguridad
- No recibe actualizaciones de compatibilidad
- No se aceptan pull requests ni issues
- No hay roadmap de futuras versiones

### 2. Incompatibilidad con el stack actual

| Dependencia | Versión en reactor-rabbitmq 1.5.6 | Versión en Reactive Commons 8.x |
|---|---|---|
| Reactor Core | 3.4.x | 3.7.x+ (Spring Boot 4.0.2) |
| amqp-client | 5.16.x | 5.28.0 |
| Java | 8-17 | 17-21 |

Estas diferencias generan riesgo de:

- Incompatibilidades binarias silenciosas
- Comportamientos inesperados en runtime
- Bugs que no serán corregidos upstream

### 3. Complejidad innecesaria

reactor-rabbitmq es una librería de ~15,000 líneas que incluye:

- `ChannelPool` y `ChannelPoolFactory` con lógica de cache compleja
- `FluxSink`-based publishing con `ExecutorService` dedicados
- Manejo de `ResourceManagementChannel` con proxies dinámicos
- Soporte para features que Reactive Commons no utiliza

El proyecto solo usa ~10% de la API expuesta.

---

## Alternativas evaluadas

### Opción 1: Mantener reactor-rabbitmq (descartada)

**Pros:**
- Cero esfuerzo de migración

**Contras:**
- Dependencia muerta sin fixes de seguridad
- Riesgo creciente de incompatibilidad con cada actualización de Spring Boot / Reactor
- No hay soporte oficial para Java 21+

### Opción 2: Usar Vert.x RabbitMQ Client (descartada)

Se evaluó `io.vertx:vertx-rabbitmq-client:5.0.8` como reemplazo.

**Pros:**
- Proyecto activo con releases frecuentes
- API reactiva nativa (basada en `Future`)

**Contras:**
- **SSL/TLS problemático**: Vert.x no carga automáticamente el trust store de la JVM (`cacerts`). Requiere configuración explícita de `JksOptions`/`PfxOptions`, lo que causa fallos de conexión con brokers como **AWS Amazon MQ** que usan certificados de CAs públicas (Amazon Trust Services)
- **Overhead innecesario**: Agrega Vert.x Core (~1.5MB) + vertx-rabbitmq-client al classpath
- **Bridging costoso**: Requiere convertir `io.vertx.core.Future` → `CompletionStage` → `Mono`, agregando overhead y complejidad en stack traces
- **Modelo de concurrencia diferente**: El event loop de Vert.x es un paradigma ajeno al modelo Spring Boot + Reactor del proyecto
- **Canal único**: `RabbitMQClient` de Vert.x maneja un solo canal interno, limitando la concurrencia comparado con el modelo multi-canal de amqp-client

### Opción 3: Clases bridge sobre amqp-client + Netty (seleccionada)

**Pros:**
- **SSL funciona out-of-the-box**: `SslContextBuilder.forClient()` de Netty + `TrustManagerFactory.init(null)` carga automáticamente los cacerts de la JVM
- **Cero dependencias adicionales**: `com.rabbitmq:amqp-client` ya era dependencia transitiva
- **Control total**: Código propio, corregible en minutos
- **Alineación con el ecosistema**: `ConnectionFactory` es el estándar de Spring AMQP y RabbitMQ
- **Transporte eficiente**: Netty NIO compartido via `EventLoopGroup`, [documentado oficialmente por RabbitMQ](https://www.rabbitmq.com/client-libraries/java-api-guide#netty)

**Contras:**
- ~410 líneas de código propio a mantener (riesgo bajo dado que la API de amqp-client es estable)

---

## Diseño de las clases bridge

### Arquitectura

```
┌─────────────────────────────────────────────────────┐
│                 Reactive Commons                     │
│         (ReactiveMessageSender, Listeners, etc.)     │
├─────────────────────────────────────────────────────┤
│                  Clases Bridge                       │
│   Sender, Receiver, AcknowledgableDelivery, etc.    │
│         (~410 líneas, API reactiva Mono/Flux)        │
├─────────────────────────────────────────────────────┤
│              com.rabbitmq:amqp-client                │
│       ConnectionFactory, Channel, Connection         │
│            + Netty NIO Transport (SSL)               │
└─────────────────────────────────────────────────────┘
```

### Clases creadas

| Clase | Líneas | Reemplaza | Responsabilidad |
|---|---|---|---|
| `Sender` | ~130 | `reactor.rabbitmq.Sender` | Declarar topología (exchanges, queues, bindings) y publicar mensajes con/sin publisher confirms |
| `Receiver` | ~120 | `reactor.rabbitmq.Receiver` | Consumir mensajes con manual ack o auto ack, cada consumidor obtiene su propio `Channel` |
| `AcknowledgableDelivery` | ~40 | `reactor.rabbitmq.AcknowledgableDelivery` | Extiende `Delivery` con `ack()` y `nack(boolean)` sobre el `Channel` del consumidor |
| `OutboundMessage` | ~10 | `reactor.rabbitmq.OutboundMessage` | POJO: exchange + routingKey + properties + body |
| `OutboundMessageResult` | ~10 | `reactor.rabbitmq.OutboundMessageResult` | POJO: outboundMessage + ack + returned |
| `ExchangeSpecification` | ~25 | `reactor.rabbitmq.ExchangeSpecification` | Builder para declarar exchanges |
| `QueueSpecification` | ~30 | `reactor.rabbitmq.QueueSpecification` | Builder para declarar queues |
| `BindingSpecification` | ~15 | `reactor.rabbitmq.BindingSpecification` | Factory para crear bindings |
| `ResourcesSpecification` | ~15 | `reactor.rabbitmq.ResourcesSpecification` | Factory estática: `exchange()`, `queue()`, `binding()` |
| `ConsumeOptions` | ~15 | `reactor.rabbitmq.ConsumeOptions` | Configuración de QoS y consumer tag |

### Principios de diseño

1. **Mínima superficie**: Solo se implementa lo que Reactive Commons realmente usa
2. **Channel-per-operation**: Cada operación de topología o publish abre un `Channel`, ejecuta, y cierra. Los consumidores mantienen su canal abierto
3. **Publisher Confirms atómicos**: `confirmSelect()` + `basicPublish()` + `waitForConfirmsOrDie()` en un solo canal dedicado
4. **Scheduling explícito**: Operaciones bloqueantes de amqp-client se ejecutan en `Schedulers.boundedElastic()` para no bloquear el event loop de Reactor
5. **Conexión compartida**: Una sola `Mono<Connection>.cache()` con retry exponencial por `ConnectionFactory`

---

## Consideraciones adicionales

### Gestión de conexiones

- Se usa un `ConcurrentMap<ConnectionFactory, Mono<Connection>>` como cache para reutilizar la conexión
- La conexión utiliza retry con backoff exponencial (`300ms` inicial, `3000ms` máximo) para reconexión automática
- El `Connection Name` incluye el `appName` + `InstanceIdentifier` para trazabilidad en el management UI de RabbitMQ

### Transporte Netty

- Se comparte un único `EventLoopGroup` (NIO) entre todas las conexiones via `SHARED_EVENT_LOOP_GROUP`
- El lifecycle del `EventLoopGroup` se gestiona con `EventLoopGroupLifecycleManager` (Spring `SmartLifecycle`) para shutdown limpio
- Referencia: [RabbitMQ Java API Guide - Use of Netty for Network I/O](https://www.rabbitmq.com/client-libraries/java-api-guide#netty)

### SSL/TLS

- Se usa `SslContextBuilder.forClient()` de Netty (no el `SSLContext` de JDK)
- `TrustManagerFactory.init(null)` carga automáticamente los certificados del trust store de la JVM
- Compatible con AWS Amazon MQ, CloudAMQP, y cualquier broker con certificados de CAs públicas sin configuración adicional
- Soporta trust store y key store explícitos vía propiedades `ssl.trustStore`, `ssl.keyStore`
- Hostname verification habilitada por defecto vía `factory.enableHostnameVerification()`

### Testing

- Las clases bridge no rompen la interfaz pública de Reactive Commons — `ReactiveMessageSender`, `ReactiveMessageListener`, `TopologyCreator` mantienen sus firmas
- Los tests de integración existentes (`acceptance/async-tests`) siguen funcionando sin cambios
- Los consumidores de la librería solo necesitan actualizar imports si referenciaban directamente tipos de `reactor.rabbitmq` (ver [Guía de Migración](migration-guides.md))

### Monitoreo y observabilidad

- `reactor-core-micrometer` sigue instrumentando las operaciones reactivas (Mono/Flux)
- Las métricas de amqp-client (`MicrometerMetricsCollector`) están disponibles si se necesitan métricas a nivel de conexión/canal
- Los nombres de conexión incluyen el `appName` para identificación en RabbitMQ Management

### Riesgos y mitigaciones

| Riesgo | Probabilidad | Mitigación |
|---|---|---|
| Cambio en API de amqp-client Channel | Muy baja — API estable desde hace 10+ años | Las clases bridge son simples de actualizar |
| Leak de canales | Baja — cada operación usa try/finally | Monitorear canal count en RabbitMQ Management |
| Bloqueo en `waitForConfirmsOrDie` | Baja — timeout de 5s configurado | Schedulers.boundedElastic() evita bloquear event loop |
| Cambio en Netty SslContext API | Muy baja — API estable | Spring Boot BOM gestiona versiones de Netty |

### Futuras mejoras posibles

1. **Channel pooling**: Para escenarios de alto throughput, se podría implementar un pool de canales en `Sender` en lugar de crear uno por operación
2. **Batch confirms**: Agrupar múltiples publishes en un solo canal con confirm para reducir round-trips
3. **Métricas propias**: Instrumentar `Sender` y `Receiver` con Micrometer para métricas específicas de publish/consume
4. **Connection recovery**: Evaluar si `ConnectionFactory.setAutomaticRecoveryEnabled(true)` complementa o reemplaza el retry de `Mono<Connection>`

---

## Resumen de cambios en dependencias

```diff
# async-rabbit.gradle

- api 'io.projectreactor.rabbitmq:reactor-rabbitmq:1.5.6'
+ # Eliminada: reactor-rabbitmq (deprecated/archived)
+ # Se usan clases bridge propias sobre com.rabbitmq:amqp-client
  api 'com.rabbitmq:amqp-client'
```

No se agregan nuevas dependencias. Se elimina una dependencia deprecada.
