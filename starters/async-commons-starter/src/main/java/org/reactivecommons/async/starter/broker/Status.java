package org.reactivecommons.async.starter.broker;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class Status {
    private final boolean up;
    private final String domain;
    private final String details; // version or error
}
