package sample;

import io.cloudevents.CloudEvent;
import org.reactivecommons.async.api.HandlerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import sample.model.Constants;

@Configuration
public class HandlersConfig {
    @Bean
    @Primary
    public HandlerRegistry handlerRegistrySubs(UseCase useCase) {
        return HandlerRegistry.register()
                .serveQuery(Constants.GET_TEAMS, useCase::getTeams, CloudEvent.class)
                .serveQuery(Constants.GET_TEAM_MEMBERS, useCase::getTeam, CloudEvent.class)
                .handleCommand(Constants.ADD_MEMBER, useCase::addMember, CloudEvent.class)
                .listenEvent(Constants.MEMBER_REMOVED, useCase::removeMember, CloudEvent.class)
                .listenDomainEvent("domain-a", Constants.MEMBER_REMOVED_EXTERNAL_DOMAIN, useCase::removeMember, CloudEvent.class)
                .listenNotificationEvent(Constants.DATA_RESET, useCase::reset, String.class);
    }
}
