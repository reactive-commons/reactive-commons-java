package sample;

//import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
//import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.reactivecommons.async.impl.config.MessageConfig;
import org.reactivecommons.async.impl.RabbitDirectAsyncGateway;
import org.reactivecommons.api.domain.Command;

@Configuration
@Import(MessageConfig.class)
public class StandaloneClient {

//    @Bean
//    public RabbitTemplate rabbitTemplate() {
//        CachingConnectionFactory factory = new CachingConnectionFactory("127.0.0.1");
//        factory.setUsername("guest");
//        factory.setPassword("guest");
//        RabbitTemplate template = new RabbitTemplate(factory);
//        return template;
//    }
//
//    public static void main(String[] args) throws InterruptedException {
//        ApplicationContext context = new AnnotationConfigApplicationContext(StandaloneClient.class);
//        final RabbitDirectAsyncGateway asyncGateway = context.getBean(RabbitDirectAsyncGateway.class);
//        Command<Simple> command = new Command<>("test", "21312", new Simple("txt"));
//        asyncGateway.sendCommand(command, "Prueba").subscribe(aVoid -> System.out.println("ENviado"));
//        System.out.println(asyncGateway);
//        Thread.sleep(5000);
//    }

}

class Simple {
    private String txt;

    public Simple(String txt) {
        this.txt = txt;
    }

    public String getTxt() {
        return txt;
    }

    public void setTxt(String txt) {
        this.txt = txt;
    }
}
