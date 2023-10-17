package sample;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import lombok.AllArgsConstructor;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.rabbit.converters.json.CloudEventBuilderExt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

@AllArgsConstructor
@Component
public class UseCase {
    private final DirectAsyncGateway directAsyncGateway;

    public Mono<Void> sendCommand(CloudEvent event) {
        CloudEvent command = CloudEventBuilder.v1() //
                .withId(UUID.randomUUID().toString()) //
                .withSource(URI.create("https://spring.io/foos"))//
                .withType("command") //
                .withTime(OffsetDateTime.now())
                .withData("application/json", CloudEventBuilderExt.asBytes(event.getData()))
                .build();

        return directAsyncGateway.sendCommand(command, "receiver");
    }
}
