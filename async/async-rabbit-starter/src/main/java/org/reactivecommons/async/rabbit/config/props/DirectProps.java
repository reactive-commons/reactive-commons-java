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
public class DirectProps {

    @Builder.Default
    private String exchange = "directMessages";

    @Builder.Default
    private String querySuffix = "query";

    @Builder.Default
    private String commandSuffix = "";

    @Builder.Default
    private Optional<Integer> maxLengthBytes = Optional.empty();

    @Builder.Default
    private boolean discardTimeoutQueries = false;

}
