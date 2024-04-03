package org.reactivecommons.async.rabbit.config.props;

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
public class FluxProps {

    @Builder.Default
    private Integer maxConcurrency = 250;

}
