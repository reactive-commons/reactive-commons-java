package org.reactivecommons.async.kafka.config.props;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ApicurioRegistryDefinition {

    /**
     * Name of the registry, used only to report which declaration a failure belongs to.
     */
    private String name;

    /**
     * Apicurio settings shared by every topic of this registry, using their original Apicurio keys.
     */
    @Builder.Default
    private Map<String, String> properties = new HashMap<>();

    /**
     * Topics of the domain validated against this registry. A topic listed nowhere is not validated.
     */
    @NestedConfigurationProperty
    @Builder.Default
    private List<ApicurioTopicDefinition> topics = new ArrayList<>();
}

