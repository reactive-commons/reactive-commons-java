package org.reactivecommons.async.rabbit.health;

public class RabbitMQHealthException extends RuntimeException {
    public RabbitMQHealthException(Throwable throwable) {
        super(throwable);
    }
}
