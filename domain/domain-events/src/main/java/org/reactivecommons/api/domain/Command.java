package org.reactivecommons.api.domain;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Command<T> {
    private final String name;
    private final String commandId;
    private final T data;
}
