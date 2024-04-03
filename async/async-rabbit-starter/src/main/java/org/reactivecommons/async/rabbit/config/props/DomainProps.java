package org.reactivecommons.async.rabbit.config.props;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DomainProps {

    @NestedConfigurationProperty
    @Builder.Default
    private EventsProps events = new EventsProps();

}
