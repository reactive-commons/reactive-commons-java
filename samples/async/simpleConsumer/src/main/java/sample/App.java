package sample;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.*;
import reactor.util.context.Context;

import java.time.Duration;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Bean
    public CommandLineRunner runner() {
        return args -> {

//            ConnectionFactory factory = new ConnectionFactory();
//            factory.setTopologyRecoveryEnabled(true);
//            factory.setAutomaticRecoveryEnabled(true);
//            final Connection connection = factory.newConnection();
//            final Channel channel = connection.createChannel();
//            channel.decla
//            Mono<? extends Connection> connection = Mono.defer(() -> Mono.error(new RuntimeException("Some Connection Error")));
//            final Receiver receiver = ReactorRabbitMq.createReceiver(new ReceiverOptions().connectionMono(connection));
//
//            try {
//                receiver.consumeManualAck("q1").subscribeOn(Schedulers.parallel()).subscribe(acknowledgableDelivery -> {
//                    System.out.println("On Next");
//                }, throwable -> {
//                    System.out.println("Should reach this point.");
//                }, () -> {
//                    System.out.println("On Complete");
//                });
//            } catch (Exception e) {
//                System.out.println("Should no reach this point: " + e.getMessage());
//            }
//
//            Thread.sleep(5000);

//            final Sender sender = ReactorRabbitMq.createSender();
//            ReactorRabbitMq.createReceiver(new ReceiverOptions());
//            final Receiver receiver = ReactorRabbitMq.createReceiver();
//            receiver.consumeAutoAck("TestQueue").map(delivery -> {
//                System.out.println(delivery);
//                return delivery;
//            }).subscribe(delivery -> {
//                System.out.println(new String(delivery.getBody()));
//            }, throwable -> {
//                System.out.println("Error en el consumer");
//                throwable.printStackTrace();
//            }, () -> {
//                System.out.println("onComplete");
//            });
//
//            final Receiver receiver1 = ReactorRabbitMq.createReceiver(new ReceiverOptions());
////            final Sender sender = ReactorRabbitMq.createSender();
//
//            final Flux<OutboundMessage> messageFlux = Flux.interval(Duration.ofSeconds(1))
//                .flatMap(aLong -> Mono.zip(Mono.just(aLong), Mono.subscriberContext().map(context -> context.get("name"))))
//                .map(o ->
//                    new OutboundMessage("", "TestQueue", ("Hola " + o.getT1() + o.getT2()).getBytes()));
//
//
//            messageFlux.subscriberContext(Context.of("name", "Manual")).flatMapSequential(outboundMessage ->
//                sender.sendWithPublishConfirms(Mono.just(outboundMessage)).retryBackoff(40, Duration.ofMillis(1000), Duration.ofMillis(1000))
//            ).subscribe();
//
//            sender.sendWithPublishConfirms(messageFlux.subscriberContext(Context.of("name", "C2"))).subscribe();

        };
    }
}
