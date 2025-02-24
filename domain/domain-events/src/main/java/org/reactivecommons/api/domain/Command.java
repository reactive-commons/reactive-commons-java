package org.reactivecommons.api.domain;


import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class Command<T> {
    private final String name;
    private final String commandId;
    private final T data;
}
