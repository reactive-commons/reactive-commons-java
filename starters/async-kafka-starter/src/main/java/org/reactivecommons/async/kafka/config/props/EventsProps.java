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
public class EventsProps {

    /**
     * Suffix used to build the consumer group id of the domain events listener when no explicit
     * group id is set in the connection properties (consumer.group-id). The resulting group id
     * will be ${spring.application.name}-${eventsSuffix}.
     */
    @Builder.Default
    private String eventsSuffix = "events";
}
