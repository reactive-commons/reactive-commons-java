package us.sofka.commons.reactive.async;

import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Mono;

/**
 * Publicador simple de mensajes en RabbitMQ, se asume y delega la disponibilidad en el broker
 * Evolución: "Ser resiliente en la mayor medida posible a particiones de red con el broker"
 */
public abstract class EventSender {


    protected <C> Mono<Void> send(C command, String commandId) {
        return Mono.create(s -> {
            try {
                if(channel().send(MessageBuilder.withPayload(command)
                    .setHeader("x-command-id", commandId)
                    .build(), 5000)){
                    s.success();
                }else{
                    s.error(new RuntimeException("Message can't be send!"));
                }
            } catch (Exception e) {
                s.error(e);
            }

        });
    }

    protected abstract MessageChannel channel();


}
