package sample;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import sample.model.Members;
import sample.model.Team;
import sample.model.Teams;
import sample.model.broker.AddMemberCommand;
import sample.model.broker.RemovedMemberEvent;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class UseCase {
    private final Teams teams = new Teams();

    public Mono<Teams> getTeams(String ignored) {
        return Mono.fromSupplier(() -> teams);
    }

    public Mono<Members> getTeam(String teamName) {
        return Mono.fromSupplier(() -> {
            if (teams.containsKey(teamName)) {
                return teams.get(teamName).getMembers();
            }
            return new Members();
        });
    }

    public Mono<Void> addMember(Command<AddMemberCommand> command) {
        return Mono.fromRunnable(() -> teams.computeIfAbsent(command.getData().getTeamName(),
                        (name) -> Team.builder().name(command.getData().getTeamName()).members(new Members()).build())
                .getMembers().add(command.getData().getMember()));
    }

    public Mono<Void> removeMember(DomainEvent<RemovedMemberEvent> event) {
        return Mono.fromRunnable(() -> Optional.of(teams.get(event.getData().getTeamName()))
                .ifPresent(team -> team.getMembers()
                        .removeIf(member -> member.getUsername().equals(event.getData().getUsername()))));
    }

    public Mono<Void> reset(DomainEvent<String> ignored) {
        return Mono.fromRunnable(teams::clear);
    }
}
