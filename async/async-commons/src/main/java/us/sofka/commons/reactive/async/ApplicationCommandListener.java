package us.sofka.commons.reactive.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.LongString;
import com.rabbitmq.client.impl.LongStringHelper;
import lombok.Data;
import lombok.extern.java.Log;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.Receiver;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

@Log
public class ApplicationCommandListener {


    private final RabbitTemplate rabbitTemplate;

    private final Receiver receiver;
    private final String queueName;

    private final ConcurrentHashMap<String, CommandExecutor> executors = new ConcurrentHashMap<>();


//    private final WorkQueueProcessor<Message<?>> processor = WorkQueueProcessor.<Message<?>>builder().bufferSize(2048).build();
//    private final FluxSink<Message<?>> sink = processor.sink();

    private final ObjectMapper mapper = new ObjectMapper();

//    @Autowired
    private final Environment env;

//    @Autowired
    private final ApplicationContext context;

    public ApplicationCommandListener(RabbitTemplate rabbitTemplate, Receiver receiver, String queueName, Environment env, ApplicationContext context) {
        this.rabbitTemplate = rabbitTemplate;
        this.receiver = receiver;
        this.queueName = queueName;
        this.env = env;
        this.context = context;
    }

    //    @StreamListener(Sink.INPUT)
//    public void receive(Message<String> msg) throws IOException {
////        List<HashMap> xDeath = (List<HashMap>) msg.getHeaders().get("x-death");
////        Integer count = xDeath != null ? Integer.parseInt(xDeath.get(0).get("count").toString()) : 0;
////        if (count > 4) {
////            Channel channel = msg.getHeaders().get(AmqpHeaders.CHANNEL, Channel.class);
////            Long tag = msg.getHeaders().get(AmqpHeaders.DELIVERY_TAG, Long.class);
////            channel.basicAck(tag, false);
////        } else {
//        sink.next(msg);
////        }
//
//    }

    private CommandExecutor getExecutor(String commandId) {
        final CommandExecutor executor = executors.computeIfAbsent(commandId, s -> {
            String handlerName = env.getProperty(commandId);
            final CommandHandler handler1 = context.getBean(handlerName, CommandHandler.class);
            ParameterizedType genericSuperclass = (ParameterizedType) handler1.getClass().getGenericInterfaces()[0];
            final Class<?> commandClass = (Class<?>) genericSuperclass.getActualTypeArguments()[1];
            Function<String, Object> converter = msj -> {
                try {
                    final AsyncQueryInt asyncQueryInt = mapper.readValue(msj, AsyncQueryInt.class);
                    return mapper.treeToValue(asyncQueryInt.getQueryData(), commandClass);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };
            return new CommandExecutor(handler1, converter);
        });
        return executor;
    }



    @SuppressWarnings("unchecked")
    private Mono<AcknowledgableDelivery> handle(AcknowledgableDelivery msj) {
        final String commandId = msj.getProperties().getHeaders().get("x-command-id").toString();
        final CommandExecutor executor = getExecutor(commandId);
        final String body = new String(msj.getBody());
        return Mono.defer(() -> executor.execute(body)).flatMap(o ->
            Mono.fromCallable(() -> {
                if (o != null) {
                    String value = o instanceof String ? (String) o : mapper.writeValueAsString(o);
                    final String replyID = msj.getProperties().getHeaders().get("x-reply_id").toString();
                    final String correlationID = msj.getProperties().getHeaders().get("x-correlation-id").toString();

                    rabbitTemplate.convertAndSend("globalReply", replyID, value, msg -> {
                        msg.getMessageProperties().getHeaders().put("x-correlation-id", correlationID);
                        return msg;
                    });
                }
                return msj;
            })
        ).thenReturn(msj);
    }

    private <T> Flux<T> outerFailureProtection(Flux<T> messageFlux) {
        return messageFlux.onErrorContinue(t -> true, (throwable, elem) -> {
            if(elem instanceof AcknowledgableDelivery){
                try {
                    Mono.delay(Duration.ofMillis(350)).doOnSuccess(_n -> ((AcknowledgableDelivery) elem).nack(true)).subscribe();
                    log.log(Level.SEVERE, "Outer error protection reached for Async Consumer!! Severe Warning! ", throwable);
                    log.warning("Returning message to broker: " + ((AcknowledgableDelivery) elem).getProperties().getHeaders().toString());
                }catch (Exception e){
                    log.log(Level.SEVERE, "Error returning message in failure!", e);
                }
            }
        });
    }

    private Flux<AcknowledgableDelivery> consumeFaultTolerant(Flux<AcknowledgableDelivery> messageFlux) {
        return messageFlux.flatMap(msj ->
            handle(msj)
                .onErrorResume(err -> {
                    try {
                        log.log(Level.SEVERE, "Error encounter while processing message:", err);
                        log.warning("Returning message to broker in 200ms: " + ((AcknowledgableDelivery) msj).getProperties().getHeaders().toString());
                        log.warning(new String(msj.getBody()));
                    } catch (Exception e) {
                        log.log(Level.SEVERE, "Log Error", e);
                    }
                    return Mono.just(msj).delayElement(Duration.ofMillis(200)).doOnNext(s -> msj.nack(true));
                }).doOnSuccess(s -> msj.ack())
        );
    }

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void handleMsj() {
        receiver.consumeManualAck(queueName)
            .transform(this::consumeFaultTolerant)
            .transform(this::outerFailureProtection)
            .subscribe();

//        receiver.consumeManualAck(queueName).flatMap(msj -> {
//            final String commandId = (String) msj.getProperties().getHeaders().get("x-command-id");
//            final CommandExecutor executor = getExecutor(commandId);
//            final String body = new String(msj.getBody());
//            return executor.execute(body);
//        }).onErrorContinue((throwable, o) -> {
//
//        });
//        processor//.publishOn(Schedulers.parallel())//.share()
//            .flatMap((Message<?> message) -> {
//                String commandId = message.getHeaders().get("x-command-id", String.class);
//
//
//                final CommandExecutor executor = getExecutor(commandId);
//
////                Long tag = message.getHeaders().get(AmqpHeaders.DELIVERY_TAG, Long.class);
//                return executor.execute(new String((byte[]) message.getPayload())).doOnSuccess(o -> {
//                    try {
//                        if (o != null) {
//                            String value = o instanceof String ? (String) o : mapper.writeValueAsString(o);
//                            final String replyID = message.getHeaders().get("x-reply_id", String.class);
//                            final String correlationID = message.getHeaders().get("x-correlation-id", String.class);
//
//                            rabbitTemplate.convertAndSend("globalReply", replyID, value, msg -> {
//                                msg.getMessageProperties().getHeaders().put("x-correlation-id", correlationID);
//                                return msg;
//                            });
//                        }
//
//                        //channel.basicAck(tag, false);
//                    } catch (IOException e) {
//                        throw new RuntimeException(e);
//                    }
//                }).onErrorResume(throwable -> {
//                    final String replyID = message.getHeaders().get("x-reply_id", String.class);
//                    final String correlationID = message.getHeaders().get("x-correlation-id", String.class);
//
//                    if (replyID != null && !replyID.isEmpty() && correlationID != null && !correlationID.isEmpty()) {
//                        rabbitTemplate.convertAndSend("globalReply", replyID, ((Throwable) throwable).getMessage(), msg -> {
//                            msg.getMessageProperties().getHeaders().put("x-correlation-id", correlationID);
//                            msg.getMessageProperties().getHeaders().put("x-exception", "true");
//                            return msg;
//                        });
//                    }
//
//
////                            try {
//                    //channel.basicReject(tag, false);
//                    return Mono.just("");
////                            } catch (IOException e) {
////                                e.printStackTrace();
////                                throw new RuntimeException(e);
////                            }
//                });
//            }, 4096).log("ERROR", Level.SEVERE, SignalType.ON_ERROR).onErrorContinue((throwable, o) -> {
//            System.out.println("######3Recovered from error un flow ##############");
//        })
//            .parallel().runOn(Schedulers.parallel()).subscribe();
    }


}

@Data
class AsyncQueryInt {
    private String resource;
    private JsonNode queryData;
}
