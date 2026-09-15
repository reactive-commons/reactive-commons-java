package org.reactivecommons.async.kafka.config.props;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Apicurio Registry schema validation of a single Reactive Commons domain.
 * <p>
 * The validation is declared per topic: every registry lists the topics validated against it, and each topic may
 * override any property of its registry. A domain that declares no registry is not validated, so the starter can be
 * on the classpath while only some domains use it.
 * <p>
 * These values are only read when the {@code async-commons-kafka-apicurio-starter} dependency is present.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class ApicurioValidationProperties {

    /**
     * Registries of this domain, each one declaring the topics validated against it.
     * <p>
     * Only the declared topics are validated: a topic that is produced or consumed but is not listed is left alone,
     * which is how validation is skipped for a single topic. A declared topic is turned off with
     * {@code apicurio.registry.serde.validation-enabled: false} in its own properties.
     * <p>
     * The same topic name may be declared by another domain, against another registry. Declaring it twice inside
     * the same domain is rejected at startup, because a record only carries its topic name and neither registry
     * could be chosen.
     */
    @NestedConfigurationProperty
    @Builder.Default
    private List<ApicurioRegistry> registries = new ArrayList<>();
}
