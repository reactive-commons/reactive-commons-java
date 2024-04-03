package sample;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.rabbit.converters.json.CloudEventBuilderExt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import sample.model.Constants;
import sample.model.Members;
import sample.model.Team;
import sample.model.Teams;
import sample.model.broker.AddMemberCommand;
import sample.model.broker.RemovedMemberEvent;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Log4j2
@Component
@RequiredArgsConstructor
public class UseCase {
    private final Teams teams = new Teams();

    public Mono<CloudEvent> getTeams(CloudEvent ignored) {
        return Mono.fromSupplier(() -> {
            log.info("getTeams");
            return CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(URI.create("https://reactive-commons.org/foos"))
                    .withType("ReplyTo:" + Constants.GET_TEAMS)
                    .withTime(OffsetDateTime.now())
                    .withData("application/json", CloudEventBuilderExt.asBytes(teams))
                    .build();
        });
    }

    public Mono<CloudEvent> getTeam(CloudEvent query) {
        return Mono.fromSupplier(() -> {
            String teamName = CloudEventBuilderExt.fromCloudEventData(query, String.class);
            log.info("getTeam members of {}", teamName);
            Members members;
            if (teams.containsKey(teamName)) {
                members = teams.get(teamName).getMembers();
            } else {
                members = new Members();
            }
            return CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(URI.create("https://reactive-commons.org/foos"))
                    .withType("ReplyTo:" + Constants.GET_TEAM_MEMBERS)
                    .withTime(OffsetDateTime.now())
                    .withData("application/json", CloudEventBuilderExt.asBytes(members))
                    .build();
        });
    }

    public Mono<Void> addMember(Command<CloudEvent> command) {
        return Mono.fromRunnable(() -> {
            AddMemberCommand data = CloudEventBuilderExt.fromCloudEventData(command.getData(), AddMemberCommand.class);
            log.info("addMember {} to {}", data.getMember().getUsername(), data.getTeamName());
            teams.computeIfAbsent(data.getTeamName(),
                            (name) -> Team.builder().name(data.getTeamName()).members(new Members()).build())
                    .getMembers().add(data.getMember());
        });
    }

    public Mono<Void> removeMember(DomainEvent<CloudEvent> event) {
        return Mono.fromRunnable(() -> {
            RemovedMemberEvent data = CloudEventBuilderExt.fromCloudEventData(event.getData(), RemovedMemberEvent.class);
            log.info("removeMember {} from {}", data.getUsername(), data.getTeamName());
            Optional.of(teams.get(data.getTeamName())).ifPresent(team -> team.getMembers()
                    .removeIf(member -> member.getUsername().equals(data.getUsername())));
        });
    }

    public Mono<Void> reset(DomainEvent<String> ignored) {
        log.info("reset");
        return Mono.fromRunnable(teams::clear);
    }
}
