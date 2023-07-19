package org.reactivecommons.async.rabbit.config.props;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FluxProps {

    private Integer maxConcurrency = 250;

}
