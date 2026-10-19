package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenCommands;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenEvents;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.ListenQueries;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendDirect;
import static org.reactivecommons.async.rabbit.RabbitScenarioSupport.SendEvents;

/**
 * The scenarios of the documentation ("Connections and channels"), started the way an application does against a
 * REAL RabbitMQ broker. The channels that the broker reports for each connection are the source of truth.
 *
 * <p>Every scenario is started with its own connection names, so the broker tells them apart. The documented
 * values are printed next to the measured ones. The tests are skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class ChannelsPerScenarioRabbitContainerTest {

    private static final Class<?>[] SEND_ALL = {SendEvents.class, SendDirect.class};
    private static final Class<?>[] LISTEN_ALL = {ListenEvents.class, ListenCommands.class, ListenQueries.class};
    private static final Class<?>[] LISTEN_TWO = {ListenEvents.class, ListenCommands.class};
    private static final Class<?>[] LISTEN_ONE = {ListenEvents.class};

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    private record Scenario(String id, String description, boolean twoDomains, boolean listenReplies,
                            Class<?>[] features, int documentedApp, int documentedExtra) {
    }

    private record Measured(Scenario scenario, int app, int extra, int appConnections, int extraConnections) {
    }

    @Test
    void measuresTheChannelsOfEveryDocumentedScenario() throws Exception {
        List<Scenario> scenarios = List.of(
                new Scenario("s1a", "1. send, 1 domain, listenReplies=true", false, true, SEND_ALL, 13, 0),
                new Scenario("s1b", "1. send, 1 domain, listenReplies=false", false, false, SEND_ALL, 11, 0),
                new Scenario("s2a", "2. send, 2 domains, listenReplies=true", true, true, SEND_ALL, 18, 8),
                new Scenario("s2b", "2. send, 2 domains, listenReplies=false", true, false, SEND_ALL, 16, 6),
                new Scenario("s3a", "3. listen all, 1 domain, listenReplies=false", false, false, LISTEN_ALL, 14, 0),
                new Scenario("s3b", "3. listen all, 1 domain, listenReplies=true", false, true, LISTEN_ALL, 14, 0),
                new Scenario("s4a", "4. listen all, 2 domains", true, false, LISTEN_ALL, 19, 8),
                new Scenario("s4b", "4. listen two, 2 domains", true, false, LISTEN_TWO, 18, 8),
                new Scenario("s4c", "4. listen one, 2 domains", true, false, LISTEN_ONE, 17, 7),
                new Scenario("s5a", "5. send all + listen all, 1 domain, listenReplies=false", false, false,
                        concat(SEND_ALL, LISTEN_ALL), 16, 0),
                new Scenario("s5b", "5. send all + listen all, 1 domain, listenReplies=true", false, true,
                        concat(SEND_ALL, LISTEN_ALL), 16, 0),
                new Scenario("s6a", "6. send all + listen all, 2 domains", true, false,
                        concat(SEND_ALL, LISTEN_ALL), 21, 10),
                new Scenario("s6b", "6. send one + listen all, 2 domains", true, false,
                        concat(new Class<?>[]{SendEvents.class}, LISTEN_ALL), 20, 9),
                new Scenario("s6c", "6. send all + listen two, 2 domains", true, false,
                        concat(SEND_ALL, LISTEN_TWO), 20, 10),
                new Scenario("s6d", "6. send all + listen one, 2 domains", true, false,
                        concat(SEND_ALL, LISTEN_ONE), 19, 8),
                new Scenario("s6e", "6. send all, 2 domains", true, false, SEND_ALL, 16, 6));

        List<Measured> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            results.add(measure(scenario));
        }

        System.out.println("[REAL BROKER] channels per connection (documented -> measured)");
        for (Measured m : results) {
            Scenario s = m.scenario();
            System.out.printf("[REAL BROKER] %-62s app %2d -> %2d (%d connections)%s%n", s.description(),
                    s.documentedApp(), m.app(), m.appConnections(), s.twoDomains()
                            ? String.format("   additional %2d -> %2d (%d connections)", s.documentedExtra(),
                            m.extra(), m.extraConnections()) : "");
        }

        // Never more channels than documented, and the default domain does not carry extra senders for the discard
        // notifiers: with the same features on both domains it has the same channels as the additional one.
        for (Measured m : results) {
            assertThat(m.app()).as(m.scenario().description() + " (default domain)")
                    .isLessThanOrEqualTo(m.scenario().documentedApp());
        }
        Measured sendOnly = results.stream().filter(m -> m.scenario().id().equals("s2b")).findFirst().orElseThrow();
        assertThat(sendOnly.app()).as("send only, 2 domains: the default domain has the channels of its own sender")
                .isEqualTo(sendOnly.extra());
    }

    private Measured measure(Scenario scenario) throws Exception {
        try (AnnotationConfigApplicationContext ignored = RabbitScenarioSupport.start(RABBIT, scenario.id(),
                scenario.twoDomains(), scenario.listenReplies(), scenario.features())) {
            int app = RabbitScenarioSupport.settledChannels(RABBIT, scenario.id() + "-app-");
            int extra = scenario.twoDomains()
                    ? RabbitScenarioSupport.settledChannels(RABBIT, scenario.id() + "-extra-") : 0;
            int appConnections = RabbitScenarioSupport.connectionsOf(RABBIT, scenario.id() + "-app-");
            int extraConnections = scenario.twoDomains()
                    ? RabbitScenarioSupport.connectionsOf(RABBIT, scenario.id() + "-extra-") : 0;
            return new Measured(scenario, app, extra, appConnections, extraConnections);
        }
    }

    private static Class<?>[] concat(Class<?>[] first, Class<?>[] second) {
        List<Class<?>> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(second));
        return all.toArray(new Class<?>[0]);
    }
}
