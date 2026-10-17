package org.reactivecommons.async.rabbit.health;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.reactivecommons.async.starter.config.health.RCHealthIndicator;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.DOMAIN;
import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.UNKNOWN;
import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.VERSION;

/**
 * Reports the state of the RabbitMQ connection the application actually uses for a domain (shared by its senders and
 * listeners), instead of opening a new connection on every check.
 * <p>
 * The check only reads in-memory state of the client (no network I/O), so it honours the non-blocking contract of
 * Spring Boot's reactive health indicators and answers immediately:
 * <ul>
 *     <li>{@code UP}: the connection is open.</li>
 *     <li>{@code DOWN}: the connection has not been established yet, or it is closed (e.g. while the client
 *     automatically recovers it after a network failure).</li>
 * </ul>
 * A lost broker is detected by the client through I/O errors or missed heartbeats (see the
 * {@code requested-heartbeat} property), so the state changes within the heartbeat timeout.
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.reactive-health-indicators">Reactive Health Indicators</a>
 * @see <a href="https://www.rabbitmq.com/client-libraries/java-api-guide#connection-and-channel-lifespan">Connection and Channel Lifespan</a>
 */
public class RabbitReactiveHealthIndicator extends RCHealthIndicator {
    static final String ERROR = "error";

    private final String domain;
    private final Supplier<Optional<Connection>> connection;

    /**
     * @param domain     the domain whose connection is reported
     * @param connection supplies the established connection of the domain, empty while it is not established. It
     *                   must not block.
     */
    public RabbitReactiveHealthIndicator(String domain, Supplier<Optional<Connection>> connection) {
        this.domain = domain;
        this.connection = connection;
    }

    @Override
    public Mono<RCHealth> doHealthCheck(RCHealth.RCHealthBuilder builder) {
        return Mono.fromSupplier(() -> check(builder.withDetail(DOMAIN, domain)));
    }

    private RCHealth check(RCHealth.RCHealthBuilder builder) {
        Optional<Connection> current = connection.get();
        if (current.isEmpty()) {
            return builder.down().withDetail(ERROR, "Connection to RabbitMQ not established yet").build();
        }
        Connection conn = current.get();
        if (!conn.isOpen()) {
            return builder.down().withDetail(ERROR, "Connection to RabbitMQ is closed: " + closeReason(conn)).build();
        }
        return builder.up().withDetail(VERSION, serverVersion(conn)).build();
    }

    private static String closeReason(Connection connection) {
        ShutdownSignalException reason = connection.getCloseReason();
        return reason != null ? reason.getMessage() : UNKNOWN;
    }

    private static String serverVersion(Connection connection) {
        // received during the connection handshake, no network call
        Map<String, Object> serverProperties = connection.getServerProperties();
        Object version = serverProperties != null ? serverProperties.get(VERSION) : null;
        return version != null ? version.toString() : UNKNOWN;
    }
}
