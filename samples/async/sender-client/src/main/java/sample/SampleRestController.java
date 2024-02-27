package sample;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.rabbit.converters.json.CloudEventBuilderExt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
public class SampleRestController {

    @Autowired
    private DirectAsyncGateway directAsyncGateway;

    @Autowired
    private DomainEventBus domainEventBus;
    private final String queryName = "query1";
    private final String queryName2 = "query2";
    private final String target = "receiver";

    private final WebClient webClient = WebClient.builder().build();

    @PostMapping(path = "/sample", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<CloudEvent> sampleService(@RequestBody Call call) throws JsonProcessingException {
//        AsyncQuery<?> query = new AsyncQuery<>(queryName, call);
        CloudEvent query = CloudEventBuilder.v1() //
                .withId(UUID.randomUUID().toString()) //
                .withSource(URI.create("https://spring.io/foos"))//
                .withType(queryName) //
                .withTime(OffsetDateTime.now())
                .withData("application/json", CloudEventBuilderExt.asBytes(call))
                .build();

        return directAsyncGateway.requestReply(query, target, CloudEvent.class, "accounts");
    }

    @PostMapping(path = "/sample/event", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> sampleServiceEvent(@RequestBody Call call) throws JsonProcessingException {
//        AsyncQuery<?> query = new AsyncQuery<>(queryName, call);
//        CloudEvent event = CloudEventBuilder.v1() //
//                .withId(UUID.randomUUID().toString()) //
//                .withSource(URI.create("https://spring.io/foos"))//
//                .withType("event") //
//                .withTime(OffsetDateTime.now())
//                .withData("application/json", CloudEventBuilderExt.asBytes(call))
//                .build();
        DomainEvent<String> event = new DomainEvent<>("sample.event", UUID.randomUUID().toString(), "hello");
        return Mono.from(domainEventBus.emit(event)).thenReturn("event");
    }

    @GetMapping(path = "/sample/command", /*consumes = MediaType.APPLICATION_JSON_VALUE,*/ produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> sampleServiceCommand(/*@RequestBody Call call*/@RequestParam("delay") long delay) throws JsonProcessingException {
//        AsyncQuery<?> query = new AsyncQuery<>(queryName, call);
//        CloudEvent command = CloudEventBuilder.v1() //
//                .withId(UUID.randomUUID().toString()) //
//                .withSource(URI.create("https://spring.io/foos"))//
//                .withType("command") //
//                .withTime(OffsetDateTime.now())
//                .withData("application/json", CloudEventBuilderExt.asBytes(call))
//                .build();


        return directAsyncGateway.sendCommand(new Command<String>("unlock", "jhkj", "userId"), target, delay).thenReturn("command");
    }

    @PostMapping(path = "/sample/match", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<RespQuery1> sampleServices(@RequestBody Call call) {
        AsyncQuery<?> query = new AsyncQuery<>("sample.query.any.that.matches", call);
        return directAsyncGateway.requestReply(query, target, RespQuery1.class);
    }

    @PostMapping(path = "/sample2", consumes = MediaType.APPLICATION_JSON_VALUE, produces =
            MediaType.APPLICATION_JSON_VALUE)
    public Mono<RespQuery1> sampleServiceDelegate(@RequestBody Call call) {
        AsyncQuery<?> query = new AsyncQuery<>(queryName2, call);
        return directAsyncGateway.requestReply(query, target, RespQuery1.class);
    }

    @PostMapping(path = "/sample_http", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<RespQuery1> sampleServiceHttp(@RequestBody Call call) {
        DummyQuery dummyQuery = new DummyQuery(queryName, call);
        final Mono<RespQuery1> response = webClient.post().uri("http://127.0.0.1:4004/sample_destination")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dummyQuery)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(RespQuery1.class);
        return response;
    }

    @PostMapping(path = "/sample_destination", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<RespQuery1> sampleReceiver(@RequestBody DummyQuery query) {
        RespQuery1 respQuery1 = new RespQuery1("OK", query.getCall());
        return Mono.just(respQuery1);
    }


    @Data
    @AllArgsConstructor
    static class RespQuery1 {
        private String response;
        private Call request;
    }


    @Data
    @AllArgsConstructor
    static class Call {
        private String name;
        private String phone;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class DummyQuery {
        private String resource;
        private Call call;
    }
}
