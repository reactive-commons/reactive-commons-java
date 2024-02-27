package org.reactivecommons.async.rabbit.health;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class Status {
    private final String version;
    private final String domain;
}
