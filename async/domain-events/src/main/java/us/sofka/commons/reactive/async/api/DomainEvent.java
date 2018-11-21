package us.sofka.commons.reactive.async.api;

public class DomainEvent<T> {

    private final String name;
    private final String eventId;
    private final T data;

    public DomainEvent(String name, String eventId, T data) {
        this.name = name;
        this.eventId = eventId;
        this.data = data;
    }

    public String getName() {
        return name;
    }

    public String getEventId() {
        return eventId;
    }

    public T getData() {
        return data;
    }
}
