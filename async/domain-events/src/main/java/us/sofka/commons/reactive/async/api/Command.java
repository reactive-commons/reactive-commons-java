package us.sofka.commons.reactive.async.api;

public class Command<T> {

    private final String name;
    private final String commandId;
    private final T data;

    public Command(String name, String commandId, T data) {
        this.name = name;
        this.commandId = commandId;
        this.data = data;
    }

    public String getName() {
        return name;
    }

    public String getCommandId() {
        return commandId;
    }

    public T getData() {
        return data;
    }
}
