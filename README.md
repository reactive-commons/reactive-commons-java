![](https://github.com/reactive-commons/reactive-commons-java/workflows/reactive-commons-ci-cd/badge.svg)
[![Reactor RabbitMQ](https://maven-badges.herokuapp.com/maven-central/org.reactivecommons/async-commons-rabbit-starter/badge.svg)](https://mvnrepository.com/artifact/org.reactivecommons/async-commons-rabbit-starter)
# reactive-commons-java
The purpose of reactive-commons is to provide a set of abstractions and implementations over different patterns and practices that make the foundation of a reactive microservices architecture.

Docs: [https://bancolombia.github.io/reactive-commons-java/](https://bancolombia.github.io/reactive-commons-java)

Other projects: https://github.com/bancolombia

Sponsor by: https://medium.com/bancolombia-tech

Even though the main purpose is to provide such abstractions in a mostly generic way such abstractions would be of little use without a concrete implementation so we provide some implementations in a best effors maner that aim to be easy to change, personalize and extend.

The first approach to this work was to release a very simple abstractions and a corresponding implementation over asyncronous message driven communication between microservices build on top of project-reactor and spring boot.


# Actualizacón Reactive Commons
Este es el proyecto Reactive Commos el cual es una libreria utilizada para la conexión al broker de RabbitMQ y Kafka. Esta usando las dependencias de Spring Boot 4, Reactor y Reactor-RabbitMQ #fetch https://github.com/spring-attic/reactor-rabbitmq el cual esta deprecado.

# Requerimientos
Necesito actualizar la implementación de Reactor-RabbitMQ por RabbitMQ Client for Eclipse Vert.x #fetch https://vertx.io/docs/vertx-rabbitmq-client/java/. Para esto primero entender la documentaación completa del proyecto que esta en la carpeta /docs que esta realizada con docusaurus para identificar que partes van a cambiar. Deberia cambiar por ejemplo para escuchar colas (8-handling-queues.md) la configuración para ### Listening queues with custom topology, los ejemplos que estan en ## Queue configuration examples.

# Dependencias
- Reemplazar Reactor-RabbitMQ #fetch https://github.com/spring-attic/reactor-rabbitmq  por RabbitMQ Client for Eclipse Vert.x
- Mantener depedncias de Spring Boot 4, Reactor core, Jackson 3
- Tener en cuenta las guias de migración para Spring Boot 4 #fetch https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide Jackson 3. #fetch https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md

# Verificación de Cambios
- Realizar los cambios y verificar que el proyecto compile y se publique correctamente en Maven Local. Ejecutar el comando de Gradle por ejemplo `./gradlew clean publishToMavenLocal` e ignorar las pruebas unitarias.
- Una vez el se publique la versíon en Maven Local ingresar a la ruta /Users/lugomez/repos/poc-reactive-commons/ms_sender y ejecutar el microservicio para verificar los cambios realizados. El microservicio debe inciar correctamente y verificar que no debe generar logs de error y/o excepciones.
- La documentación se esta generando con Docusaurus. Actualizar la documentación de las secciones que cambian agregando por ejemplo un Tab para indicar la documentación actual con la versión 7 y la documentación con la versión 8. Actualizar Guia de migración siguiendo el estandar de las otras versiones en el archivo migration-guides.md. Ejecutar el comando `npm run build` para asegurarse que los cambios realizados no generan errores de compilación.
