package org.reactivecommons.async.rabbit;

import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.impl.config.annotations.EnableCommandListeners;
import org.reactivecommons.async.impl.config.annotations.EnableDirectAsyncGateway;
import org.reactivecommons.async.impl.config.annotations.EnableDomainEventBus;
import org.reactivecommons.async.impl.config.annotations.EnableEventListeners;
import org.reactivecommons.async.impl.config.annotations.EnableQueryListeners;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.reactivecommons.async.starter.config.ReactiveCommonsListenersConfig;
import org.reactivecommons.async.starter.impl.common.rabbit.RabbitMQConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;

/**
 * Starts the starter the way an application does (the same {@code @Enable...} annotations and the same
 * configuration classes) against a real RabbitMQ broker, and reads from the broker how many channels each
 * connection has.
 */
final class RabbitScenarioSupport {

    static final String DEFAULT_DOMAIN = "app";
    static final String EXTRA_DOMAIN = "extra";

    private RabbitScenarioSupport() {
    }

    // The annotations of the documentation, as configuration classes
    @Configuration
    @EnableDomainEventBus
    static class SendEvents {
    }

    @Configuration
    @EnableDirectAsyncGateway
    static class SendDirect {
    }

    @Configuration
    @EnableEventListeners
    static class ListenEvents {
    }

    @Configuration
    @EnableCommandListeners
    static class ListenCommands {
    }

    @Configuration
    @EnableQueryListeners
    static class ListenQueries {
    }

    /**
     * One event, one command and one query handler on the default domain, and one event and one command handler
     * on the additional domain, so every listener has something to listen to.
     */
    @Configuration
    static class Handlers {
        @Bean
        HandlerRegistry handlerRegistry() {
            return HandlerRegistry.register()
                    .listenEvent("scenario.event", event -> Mono.empty(), Map.class)
                    .handleCommand("scenario.command", command -> Mono.empty(), Map.class)
                    .serveQuery("scenario.query", query -> Mono.just("ok"),
                            String.class)
                    .listenDomainEvent(EXTRA_DOMAIN, "scenario.event", event -> Mono.empty(), Map.class)
                    .handleCommand(EXTRA_DOMAIN, "scenario.command", command -> Mono.empty(), Map.class);
        }
    }

    /**
     * An application context with its own connection names ({@code <prefix>-app-...}, {@code <prefix>-extra-...}).
     */
    static AnnotationConfigApplicationContext start(RabbitMQContainer rabbit, String prefix, boolean twoDomains,
                                                    boolean listenReplies, Class<?>... features) {
        return startWithHandlers(rabbit, prefix, twoDomains, listenReplies, Handlers.class, features);
    }

    /**
     * Same as above, with the configuration class that registers the handlers of the application.
     */
    static AnnotationConfigApplicationContext startWithHandlers(RabbitMQContainer rabbit, String prefix,
                                                                boolean twoDomains, boolean listenReplies,
                                                                Class<?> handlers, Class<?>... features) {
        return startWithHandlers(rabbit, prefix, twoDomains, listenReplies, Map.of(), handlers, features);
    }

    /**
     * Same as above, with additional properties (e.g. {@code app.async.app.direct.discardTimeoutQueries}), which win
     * over the defaults of the scenario.
     */
    static AnnotationConfigApplicationContext startWithHandlers(RabbitMQContainer rabbit, String prefix,
                                                                boolean twoDomains, boolean listenReplies,
                                                                Map<String, Object> extraProperties,
                                                                Class<?> handlers, Class<?>... features) {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.application.name", prefix);
        properties.put("spring.rabbitmq.host", rabbit.getHost());
        properties.put("spring.rabbitmq.port", rabbit.getAmqpPort());
        properties.put("spring.rabbitmq.username", rabbit.getAdminUsername());
        properties.put("spring.rabbitmq.password", rabbit.getAdminPassword());
        domain(properties, DEFAULT_DOMAIN, prefix + "-app", listenReplies);
        if (twoDomains) {
            domain(properties, EXTRA_DOMAIN, prefix + "-extra", listenReplies);
        }
        properties.putAll(extraProperties);
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("scenario", properties));
        context.register(handlers, RabbitMQConfig.class, AsyncPropsDomain.class,
                RabbitMQBrokerProviderFactory.class, ReactiveCommonsConfig.class,
                ReactiveCommonsListenersConfig.class);
        context.register(features);
        context.refresh();
        return context;
    }

    private static void domain(Map<String, Object> properties, String domain, String appName,
                               boolean listenReplies) {
        properties.put("app.async." + domain + ".appName", appName);
        properties.put("app.async." + domain + ".listenReplies", String.valueOf(listenReplies));
    }

    /**
     * Channels that the broker reports for the connection named {@code <connectionNamePrefix>...}, once the number
     * stops changing: the flows of a sender and the listeners obtain their channels asynchronously.
     */
    static int settledChannels(RabbitMQContainer rabbit, String connectionNamePrefix) {
        AtomicInteger last = new AtomicInteger(-1);
        return await().alias("the channels of '" + connectionNamePrefix + "' settled")
                .during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> channelsOf(rabbit, connectionNamePrefix).orElse(-1),
                        current -> current > 0 && current == last.getAndSet(current));
    }

    /**
     * Waits until every queue has a consumer: the listeners declare their topology and start consuming
     * asynchronously, and what is published before that has nowhere to go.
     */
    static void awaitConsumers(RabbitMQContainer rabbit, String... queues) {
        await().alias("a consumer on each of the queues " + String.join(", ", queues)).atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200)).until(() -> {
                    String listing = rabbit.execInContainer("rabbitmqctl", "-q", "list_queues", "name", "consumers")
                            .getStdout();
                    return Arrays.stream(queues).allMatch(queue -> listing.lines().anyMatch(line -> {
                        String[] columns = line.trim().split("\\s+");
                        return columns.length == 2 && columns[0].equals(queue) && !columns[1].equals("0");
                    }));
                });
    }

    /**
     * Connections that the broker reports with the name {@code <connectionNamePrefix>...}: a domain has one to
     * publish ({@code ...-sender}) and, once it listens, one to consume ({@code ...-listener}).
     */
    static int connectionsOf(RabbitMQContainer rabbit, String connectionNamePrefix) throws Exception {
        ExecResult result = rabbit.execInContainer("rabbitmqctl", "-q", "list_connections", "client_properties");
        return (int) result.getStdout().lines().filter(line -> line.contains("\"" + connectionNamePrefix)).count();
    }

    static Optional<Integer> channelsOf(RabbitMQContainer rabbit, String connectionNamePrefix) throws Exception {
        // client_properties holds the client-provided connection name; columns are tab separated
        ExecResult result = rabbit.execInContainer("rabbitmqctl", "-q", "list_connections", "client_properties",
                "channels");
        // a domain has a sender and a listener connection: its channels are the sum of both
        Optional<Integer> channels = Optional.empty();
        for (String line : result.getStdout().split("\n")) {
            String[] columns = line.trim().split("\t");
            if (columns.length >= 2 && line.contains("\"" + connectionNamePrefix)) {
                int connectionChannels = Integer.parseInt(columns[columns.length - 1].trim());
                channels = Optional.of(channels.orElse(0) + connectionChannels);
            }
        }
        return channels;
    }
}
