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
 * Apicurio Registry schema validation of a single domain.
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
     */
    @NestedConfigurationProperty
    @Builder.Default
    private List<ApicurioRegistry> registries = new ArrayList<>();
}
