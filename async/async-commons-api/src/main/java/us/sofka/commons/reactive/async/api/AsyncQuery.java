package us.sofka.commons.reactive.async.api;

import lombok.Data;

@Data
public class AsyncQuery<T> {
    private final String resource;
    private final T queryData;
}
