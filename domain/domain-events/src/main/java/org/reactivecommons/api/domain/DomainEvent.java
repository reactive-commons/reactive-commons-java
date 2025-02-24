package org.reactivecommons.api.domain;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class DomainEvent<T> {
    private final String name;
    private final String eventId;
    private final T data;
}
