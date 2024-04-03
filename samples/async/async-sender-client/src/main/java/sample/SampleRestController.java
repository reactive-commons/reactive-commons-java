package sample;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import sample.model.Constants;
import sample.model.Member;
import sample.model.Members;
import sample.model.Teams;
import sample.model.broker.AddMemberCommand;
import sample.model.broker.RemovedMemberEvent;

import java.util.UUID;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@RestController
@RequiredArgsConstructor
public class SampleRestController {
    private final DirectAsyncGateway directAsyncGateway;
    private final DomainEventBus domainEventBus;
    private final String target = "receiver";

    // Notification Event
    @DeleteMapping(path = "/api/teams", produces = APPLICATION_JSON_VALUE)
    public Mono<DomainEvent<String>> resetData() {
        DomainEvent<String> event = new DomainEvent<>(Constants.DATA_RESET, UUID.randomUUID().toString(), "");
        return Mono.from(domainEventBus.emit(event)).thenReturn(event);
    }

    // Query
    @GetMapping(path = "/api/teams", produces = APPLICATION_JSON_VALUE)
    public Mono<Teams> getTeams() {
        AsyncQuery<?> query = new AsyncQuery<>(Constants.GET_TEAMS, "");
        return directAsyncGateway.requestReply(query, target, Teams.class);
    }

    // Query
    @GetMapping(path = "/api/teams/{team}", produces = APPLICATION_JSON_VALUE)
    public Flux<Member> getTeamMembers(@PathVariable("team") String team) {
        AsyncQuery<?> query = new AsyncQuery<>(Constants.GET_TEAM_MEMBERS, team);
        return directAsyncGateway.requestReply(query, target, Members.class)
                .flatMapMany(Flux::fromIterable);
    }

    // Command
    @PostMapping(path = "/api/teams/{team}/members", consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    public Mono<Command<AddMemberCommand>> addMember(@PathVariable("team") String team, @RequestBody Member member) {
        Command<AddMemberCommand> command = new Command<>(Constants.ADD_MEMBER, UUID.randomUUID().toString(),
                AddMemberCommand.builder().member(member).teamName(team).build());
        return directAsyncGateway.sendCommand(command, target).thenReturn(command);
    }

    // Event
    @DeleteMapping(path = "/api/teams/{team}/members/{member}", produces = APPLICATION_JSON_VALUE)
    public Mono<DomainEvent<RemovedMemberEvent>> removeMember(@PathVariable("team") String team,
                                                              @PathVariable("member") String member) {
        DomainEvent<RemovedMemberEvent> event = new DomainEvent<>(Constants.MEMBER_REMOVED, UUID.randomUUID().toString(),
                RemovedMemberEvent.builder().teamName(team).username(member).build());
        return Mono.from(domainEventBus.emit(event)).thenReturn(event);
    }
}
