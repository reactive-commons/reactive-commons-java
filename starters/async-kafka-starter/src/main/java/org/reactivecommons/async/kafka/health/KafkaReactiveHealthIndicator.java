package org.reactivecommons.async.kafka.health;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.reactivecommons.async.kafka.listeners.GenericMessageListener;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.reactivecommons.async.starter.config.health.RCHealthIndicator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.DOMAIN;
import static org.reactivecommons.async.starter.config.health.ReactiveCommonsHealthIndicator.VERSION;

/**
 * The health of a Kafka domain: whether its cluster answers, and whether its listeners consume.
 * <p>
 * It asks the cluster for its id with the admin client of the domain, with a short timeout
 * ({@link #DEFAULT_TIMEOUT} unless configured), so it answers well within the 10 s after which Spring Boot reports a
 * slow health indicator, and the timeouts of the probes of an orchestrator. It is {@code DOWN} when the cluster does
 * not answer in time (the error is in the {@value #ERROR} detail), or when a listener has been restarting for more
 * than {@link #RESTART_GRACE_PERIOD}, that is, it cannot get its consumer to work (a broker that cannot be reached,
 * a missing permission); a listener that restarts and consumes again within that time is {@code UP}.
 * <p>
 * Since it depends on an external system, it must not be part of a liveness probe: restarting the application does
 * not fix a broker that cannot be reached. Whether it is part of a readiness probe is up to the application.
 */
@Slf4j
public class KafkaReactiveHealthIndicator extends RCHealthIndicator {
    /**
     * How long the cluster is waited for when no timeout is configured.
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    /**
     * How long a listener may be restarting before the domain is reported {@code DOWN}: its first restarts wait from
     * 200 ms to 5 s, so a transient failure (a rebalance, a broker restarting) is over well before.
     */
    public static final Duration RESTART_GRACE_PERIOD = Duration.ofSeconds(30);
    static final String ERROR = "error";
    static final String LISTENERS = "listeners";
    /**
     * Over the timeout given to the admin client, before the check is given up even if the client did not answer.
     */
    private static final Duration TIMEOUT_MARGIN = Duration.ofMillis(500);

    private final String domain;
    private final AdminClient adminClient;
    private final Duration timeout;
    private final Duration restartGracePeriod;
    private final List<GenericMessageListener> listeners = new CopyOnWriteArrayList<>();

    public KafkaReactiveHealthIndicator(String domain, AdminClient adminClient) {
        this(domain, adminClient, DEFAULT_TIMEOUT);
    }

    /**
     * @param timeout how long the cluster is waited for; {@link #DEFAULT_TIMEOUT} when {@code null}
     */
    public KafkaReactiveHealthIndicator(String domain, AdminClient adminClient, Duration timeout) {
        this(domain, adminClient, timeout, RESTART_GRACE_PERIOD);
    }

    KafkaReactiveHealthIndicator(String domain, AdminClient adminClient, Duration timeout,
                                 Duration restartGracePeriod) {
        this.domain = domain;
        this.adminClient = adminClient;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        this.restartGracePeriod = restartGracePeriod;
    }

    /**
     * Adds a listener of the domain to the health.
     */
    public void watch(GenericMessageListener listener) {
        listeners.add(listener);
    }

    /**
     * Nothing runs until the result is subscribed to, and the result never fails: an error is a {@code DOWN} health
     * with the error in its {@value #ERROR} detail.
     */
    @Override
    public Mono<RCHealth> doHealthCheck(RCHealth.RCHealthBuilder builder) {
        return Mono.defer(() -> {
            builder.withDetail(DOMAIN, domain);
            List<String> stuck = addListeners(builder);
            return clusterId()
                    .map(clusterId -> {
                        builder.withDetail(VERSION, clusterId);
                        if (stuck.isEmpty()) {
                            return builder.up().build();
                        }
                        return builder.down().withDetail(ERROR, "Listeners restarting for more than "
                                + restartGracePeriod.toSeconds() + " s, topics: " + stuck).build();
                    })
                    .onErrorResume(error -> {
                        String description = describe(error);
                        log.warn("Kafka health check of domain {} failed: {}", domain, description);
                        return Mono.just(builder.down().withDetail(ERROR, description).build());
                    });
        });
    }

    private Mono<String> clusterId() {
        return Mono.fromFuture(() -> adminClient.describeCluster(new DescribeClusterOptions()
                                .timeoutMs((int) timeout.toMillis()))
                        .clusterId()
                        .toCompletionStage()
                        .toCompletableFuture())
                // The admin client fails the call at its timeout; this bounds the check even if it does not
                .timeout(timeout.plus(TIMEOUT_MARGIN));
    }

    /**
     * Adds the state of each listener to the details: its topics and how long it has been restarting, never data of
     * the connection.
     *
     * @return the topics of the listeners restarting for longer than the grace period
     */
    private List<String> addListeners(RCHealth.RCHealthBuilder builder) {
        if (listeners.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> details = new ArrayList<>();
        List<String> stuck = new ArrayList<>();
        for (GenericMessageListener listener : listeners) {
            Duration restarting = listener.restartingFor();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("topics", listener.topics());
            detail.put("restarting", listener.isRestarting());
            detail.put("restartingSeconds", restarting.toSeconds());
            detail.put("restarts", listener.restarts());
            details.add(detail);
            if (restarting.compareTo(restartGracePeriod) > 0) {
                stuck.addAll(listener.topics());
            }
        }
        builder.withDetail(LISTENERS, details);
        return stuck;
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? cause.getClass().getName()
                : cause.getClass().getName() + ": " + cause.getMessage();
    }

    /**
     * Closes the admin client.
     *
     * @deprecated the admin client is the one of the domain, shared with its topology creator, which closes it; the
     * provider of the domain does not call this method anymore
     */
    @Deprecated(forRemoval = true, since = "7.9.0")
    public void close() {
        adminClient.close();
    }
}
