package org.reactivecommons.api.domain;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class DomainEvent<T> {
    private final String name;
    private final String eventId;
    private final T data;
}
