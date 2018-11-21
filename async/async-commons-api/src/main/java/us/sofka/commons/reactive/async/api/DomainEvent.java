package us.sofka.commons.reactive.async.api;

public class DomainEvent<T> {

    private final String name;
    private final T data;

    public DomainEvent(String name, T data) {
        this.name = name;
        this.data = data;
    }

    public String getName() {
        return name;
    }

    public T getData() {
        return data;
    }
}
