package org.reactivecommons.async.kafka.config.props;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DomainProps {
    //
//    @NestedConfigurationProperty
//    @Builder.Default
//    private EventsProps events = new EventsProps();
    @Builder.Default
    private boolean ignoreThisListener = false;
}
