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
public class GlobalProps {

    @Builder.Default
    private String exchange = "globalReply";

    @Builder.Default
    private String repliesSuffix = "replies";

    @Builder.Default
    private Optional<Integer> maxLengthBytes = Optional.empty();

}
