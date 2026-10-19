package org.reactivecommons.async.rabbit.health;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;
import org.reactivecommons.async.rabbit.communications.PublisherStatus;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.reactivecommons.async.starter.config.health.RCHealthIndicator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.DOMAIN;
import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.UNKNOWN;
import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.VERSION;

/**
 * Reports the state of the RabbitMQ connection and of the publishing flows the application actually uses for a
 * domain, instead of opening a new connection on every check.
 * <p>
 * The check only reads in-memory state of the client (no network I/O), so it honours the non-blocking contract of
 * Spring Boot's reactive health indicators and answers immediately:
 * <ul>
 *     <li>{@code UP}: the connection is open and a publisher-confirms flow is usable.</li>
 *     <li>{@code DOWN}: the connection has not been established yet, it is closed (e.g. while the client
 *     automatically recovers it after a network failure), or the connection is open but the publishing path is
 *     broken: no confirm flow has been live for a while, or the flows keep failing (e.g. cycling on a channel-level
 *     error) without a single confirmed message.</li>
 * </ul>
 * A lost broker is detected by the client through I/O errors or missed heartbeats (see the
 * {@code requested-heartbeat} property), so the state changes within the heartbeat timeout.
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.reactive-health-indicators">Reactive Health Indicators</a>
 * @see <a href="https://www.rabbitmq.com/client-libraries/java-api-guide#connection-and-channel-lifespan">Connection and Channel Lifespan</a>
 */
public class RabbitReactiveHealthIndicator extends RCHealthIndicator {
    static final String ERROR = "error";
    static final String LAST_FAILURE = "lastFailure";

    /**
     * A confirm-flow failure is a normal event (the flow re-subscribes within 200 ms - 5 s): only several failures
     * without a single confirmed message are considered a broken publishing path.
     */
    static final long BROKEN_FLOW_FAILURE_THRESHOLD = 3;
    static final Duration LAST_FAILURE_MAX_AGE = Duration.ofSeconds(60);
    static final Duration FLOWS_DOWN_GRACE = Duration.ofSeconds(10);

    private final String domain;
    private final Supplier<Optional<Connection>> connection;
    private final Supplier<PublisherStatus> publisherStatus;

    /**
     * @param domain     the domain whose connection is reported
     * @param connection supplies the established connection of the domain, empty while it is not established. It
     *                   must not block.
     */
    public RabbitReactiveHealthIndicator(String domain, Supplier<Optional<Connection>> connection) {
        this(domain, connection, PublisherStatus::healthy);
    }

    /**
     * @param domain          the domain whose connection is reported
     * @param connection      supplies the established connection of the domain, empty while it is not established. It
     *                        must not block.
     * @param publisherStatus supplies the in-memory liveness of the domain's publishing flows. It must not block.
     */
    public RabbitReactiveHealthIndicator(String domain, Supplier<Optional<Connection>> connection,
                                         Supplier<PublisherStatus> publisherStatus) {
        this.domain = domain;
        this.connection = connection;
        this.publisherStatus = publisherStatus;
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
        PublisherStatus status = publisherStatus.get();
        if (publisherBroken(status, Instant.now())) {
            return builder.down()
                    .withDetail(ERROR, publisherBrokenReason(status))
                    .withDetail(LAST_FAILURE, status.lastConfirmFailure())
                    .withDetail(VERSION, serverVersion(conn))
                    .build();
        }
        return builder.up().withDetail(VERSION, serverVersion(conn)).build();
    }

    /**
     * The connection is open, so a broken publishing path is only reported when there is evidence of it: the flows
     * keep failing without a single confirmed message, or no confirm flow has been live for longer than the
     * re-subscription grace period.
     */
    private static boolean publisherBroken(PublisherStatus status, Instant now) {
        if (status == null) {
            return false;
        }
        boolean flowsFailing = status.consecutiveConfirmFailures() >= BROKEN_FLOW_FAILURE_THRESHOLD
                && isRecent(status.lastConfirmFailure(), now);
        boolean flowsDown = !status.confirmFlowsLive()
                && status.confirmFlowsDownSince() != null
                && !status.confirmFlowsDownSince().isAfter(now.minus(FLOWS_DOWN_GRACE));
        return flowsFailing || flowsDown;
    }

    private static String publisherBrokenReason(PublisherStatus status) {
        if (!status.confirmFlowsLive() && status.confirmFlowsDownSince() != null) {
            return "RabbitMQ publishing flows are down since " + status.confirmFlowsDownSince()
                    + ": " + status.lastConfirmFailureMessage();
        }
        return "RabbitMQ publishing flows keep failing (" + status.consecutiveConfirmFailures()
                + " re-subscriptions without a confirmed message): " + status.lastConfirmFailureMessage();
    }

    private static boolean isRecent(Instant instant, Instant now) {
        return instant != null && !instant.isBefore(now.minus(LAST_FAILURE_MAX_AGE));
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
