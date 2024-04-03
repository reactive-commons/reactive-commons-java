package sample;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.rabbit.converters.json.CloudEventBuilderExt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import sample.model.Constants;
import sample.model.Member;
import sample.model.Members;
import sample.model.Teams;
import sample.model.broker.AddMemberCommand;
import sample.model.broker.RemovedMemberEvent;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;


@RestController
@RequiredArgsConstructor
public class SampleRestController {
    private final DirectAsyncGateway directAsyncGateway;
    private final DomainEventBus domainEventBus;
    private final String target = "receiver-eda";
    private final String externalDomain = "teams";

    // Query
    @GetMapping(path = "/api/teams", produces = APPLICATION_JSON_VALUE)
    public Mono<Teams> getTeams() {
        CloudEvent query = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("https://reactive-commons.org/foos"))
                .withType(Constants.GET_TEAMS)
                .withTime(OffsetDateTime.now())
                .withData("application/json", CloudEventBuilderExt.asBytes(""))
                .build();
        return directAsyncGateway.requestReply(query, target, CloudEvent.class, externalDomain)
                .map(event -> CloudEventBuilderExt.fromCloudEventData(event, Teams.class));
    }

    // Query
    @GetMapping(path = "/api/teams/{team}", produces = APPLICATION_JSON_VALUE)
    public Mono<Members> getTeamMembers(@PathVariable("team") String team) {
        CloudEvent query = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("https://reactive-commons.org/foos"))
                .withType(Constants.GET_TEAM_MEMBERS)
                .withTime(OffsetDateTime.now())
                .withData("application/json", CloudEventBuilderExt.asBytes(team))
                .build();
        return directAsyncGateway.requestReply(query, target, CloudEvent.class, externalDomain)
                .map(event -> CloudEventBuilderExt.fromCloudEventData(event, Members.class));
    }

    // Command
    @PostMapping(path = "/api/teams/{team}/members", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    public Mono<AddMemberCommand> addMember(@PathVariable("team") String team, @RequestBody Member member) {
        AddMemberCommand commandData = AddMemberCommand.builder().member(member).teamName(team).build();
        CloudEvent command = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("https://reactive-commons.org/foos"))
                .withType(Constants.ADD_MEMBER)
                .withTime(OffsetDateTime.now())
                .withData("application/json", CloudEventBuilderExt.asBytes(commandData))
                .build();
        return directAsyncGateway.sendCommand(command, target, externalDomain).thenReturn(commandData);
    }

    // Event
    @DeleteMapping(path = "/api/teams/{team}/members/{member}", produces = APPLICATION_JSON_VALUE)
    public Mono<RemovedMemberEvent> removeMember(@PathVariable("team") String team,
                                         @PathVariable("member") String member) {
        RemovedMemberEvent eventData = RemovedMemberEvent.builder().teamName(team).username(member).build();
        CloudEvent event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("https://reactive-commons.org/foos"))
                .withType(Constants.MEMBER_REMOVED_EXTERNAL_DOMAIN)
                .withTime(OffsetDateTime.now())
                .withData("application/json", CloudEventBuilderExt.asBytes(eventData))
                .build();
        return Mono.from(domainEventBus.emit(event)).thenReturn(eventData);
    }
}
