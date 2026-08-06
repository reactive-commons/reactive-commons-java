package org.reactivecommons.async.kafka.config.props;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.kafka.KafkaBrokerProviderFactory;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.reactivecommons.async.starter.impl.common.kafka.RCKafkaConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that YAML/properties values and programmatic values provided through a KafkaPropsCustomizer are merged
 * when the customizer uses customize(), instead of replacing the whole domain as put() does.
 */
@SpringBootTest(classes = {
        RCKafkaConfig.class,
        AsyncKafkaPropsDomain.class,
        KafkaBrokerProviderFactory.class,
        ReactiveCommonsConfig.class,
        AsyncKafkaPropsCustomizerMergeTest.CustomizerConfig.class
}, properties = {
        "reactive.commons.kafka.app.connectionProperties.consumer.group-id=dummy.consumer-group",
        "reactive.commons.kafka.app.retryDelay=1000"
})
class AsyncKafkaPropsCustomizerMergeTest {

    @Autowired
    private AsyncKafkaPropsDomain propsDomain;

    @Test
    void shouldKeepYamlValuesAndApplyProgrammaticOnes() {
        AsyncKafkaProps app = propsDomain.getProps("app");
        // Values coming from the customizer
        assertThat(app.getMaxRetries()).isEqualTo(2);
        assertThat(app.getRetryDelay()).isEqualTo(30000);
        assertThat(app.getConnectionProperties().getBootstrapServers()).containsExactly("localhost:9099");
        // Values coming from configuration properties are preserved
        assertThat(app.getConnectionProperties().getConsumer().getGroupId())
                .isEqualTo("dummy.consumer-group");
        assertThat(app.resolveEventsGroupId()).isEqualTo("dummy.consumer-group");
    }

    @TestConfiguration
    static class CustomizerConfig {

        @Bean
        public AsyncKafkaPropsDomain.KafkaPropsCustomizer kafkaPropsCustomizer() {
            return domainProperties -> domainProperties.customize("app", app -> {
                app.setMaxRetries(2);
                app.setRetryDelay(30000);
                app.getConnectionProperties().setBootstrapServers(List.of("localhost:9099"));
            });
        }
    }
}
