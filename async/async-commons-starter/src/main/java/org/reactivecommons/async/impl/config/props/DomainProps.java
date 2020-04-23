package org.reactivecommons.async.impl.config.props;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@Getter
@Setter
public class DomainProps {

    @NestedConfigurationProperty
    private EventsProps events = new EventsProps();

}
