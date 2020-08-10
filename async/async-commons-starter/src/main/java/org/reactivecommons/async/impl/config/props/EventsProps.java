package org.reactivecommons.async.impl.config.props;

import lombok.Getter;
import lombok.Setter;

import java.util.Optional;

@Getter
@Setter
public class EventsProps {

    private String exchange = "domainEvents";

    private Optional<Integer> maxLengthBytes = Optional.empty();

}
