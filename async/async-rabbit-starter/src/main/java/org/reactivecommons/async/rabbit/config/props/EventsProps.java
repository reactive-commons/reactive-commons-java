package org.reactivecommons.async.rabbit.config.props;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Optional;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EventsProps {

    @Builder.Default
    private String exchange = "domainEvents";
    @Builder.Default
    private String eventsSuffix = "subsEvents";
    @Builder.Default
    private String notificationSuffix = "notification";

    @Builder.Default
    private Optional<Integer> maxLengthBytes = Optional.empty();

}
