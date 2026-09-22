package org.reactivecommons.async.starter.impl.common.kafka.apicurio;

import org.junit.jupiter.api.Test;
import org.reactivecommons.async.kafka.apicurio.ApicurioSchemaValidator;
import org.reactivecommons.async.kafka.apicurio.TopicSchemaValidatorRouter;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaPropsDomain;
import org.reactivecommons.async.kafka.validation.DomainSchemaValidatorProvider;
import org.reactivecommons.async.starter.config.ReactiveCommonsConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Apicurio configuration lives under the package scanned by {@link ReactiveCommonsConfig}, so adding the
 * starter to the classpath is enough to enable schema validation.
 */
@SpringBootTest(classes = ReactiveCommonsConfig.class,
        properties = {
                "reactive.commons.kafka.app.apicurio.registries[0].name=main-registry",
                "reactive.commons.kafka.app.apicurio.registries[0].properties."
                        + "apicurio\\.registry\\.url=http://localhost:8080/apis/registry/v3",
                "reactive.commons.kafka.app.apicurio.registries[0].properties."
                        + "apicurio\\.registry\\.find-latest=true",
                "reactive.commons.kafka.app.apicurio.registries[0].topics[0].name=events-topic"})
class ApicurioComponentScanTest {

    @Autowired
    private DomainSchemaValidatorProvider schemaValidatorProvider;

    @Autowired
    private AsyncKafkaPropsDomain propsDomain;

    @Test
    void shouldDiscoverApicurioValidatorByComponentScan() {
        assertThat(schemaValidatorProvider.forDomain("app")).isInstanceOf(TopicSchemaValidatorRouter.class);
        assertThat(((TopicSchemaValidatorRouter) schemaValidatorProvider.forDomain("app"))
                .forTopic("events-topic")).isInstanceOf(ApicurioSchemaValidator.class);
    }

    @Test
    void shouldReadOnlyTheDomainsDeclaredInTheConfigurationFile() {
        assertThat(propsDomain).containsOnlyKeys("app");
    }
}

