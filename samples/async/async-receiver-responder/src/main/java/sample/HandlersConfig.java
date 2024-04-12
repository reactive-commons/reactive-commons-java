package sample;

import org.reactivecommons.async.api.HandlerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import sample.model.Constants;
import sample.model.broker.AddMemberCommand;
import sample.model.broker.RemovedMemberEvent;

@Configuration
public class HandlersConfig {
    @Bean
    @Primary
    public HandlerRegistry handlerRegistrySubs(UseCase useCase) {
        return HandlerRegistry.register()
                .listenEvent(Constants.MEMBER_REMOVED, useCase::removeMember, RemovedMemberEvent.class)
                .serveQuery(Constants.GET_TEAMS, useCase::getTeams, String.class)
                .serveQuery(Constants.GET_TEAM_MEMBERS, useCase::getTeam, String.class)
                .handleCommand(Constants.ADD_MEMBER, useCase::addMember, AddMemberCommand.class)
                .listenEvent(Constants.MEMBER_REMOVED, useCase::removeMember, RemovedMemberEvent.class)
                .handleDynamicEvents("purchase.*", useCase::removeMember, RemovedMemberEvent.class)
                .listenNotificationEvent(Constants.DATA_RESET, useCase::reset, String.class);
    }
}
