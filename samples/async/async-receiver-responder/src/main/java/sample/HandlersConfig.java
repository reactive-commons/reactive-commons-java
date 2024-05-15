package sample;

import lombok.extern.log4j.Log4j2;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;
import sample.model.Constants;
import sample.model.broker.AddMemberCommand;
import sample.model.broker.AnimalEvent;
import sample.model.broker.RemovedMemberEvent;

@Log4j2
@Configuration
public class HandlersConfig {

    private EventHandler<AnimalEvent> animalEventEventHandler;

    @Bean
    @Primary
    public HandlerRegistry handlerRegistrySubs(UseCase useCase) {
        animalEventEventHandler = event -> {
            log.info(event);
            return Mono.error(new RuntimeException("Not implemented"));
        };
        return HandlerRegistry.register()
                .listenEvent(Constants.MEMBER_REMOVED, useCase::removeMember, RemovedMemberEvent.class)
                .listenEvent(Constants.ANIMALS_MANY, animalEventEventHandler, AnimalEvent.class) // has # wildcard
                .serveQuery(Constants.GET_TEAMS, useCase::getTeams, String.class)
                .serveQuery(Constants.GET_TEAM_MEMBERS, useCase::getTeam, String.class)
                .handleCommand(Constants.ADD_MEMBER, useCase::addMember, AddMemberCommand.class)
                .handleDynamicEvents(Constants.ANIMALS, animalEventEventHandler, AnimalEvent.class)
                .listenNotificationEvent(Constants.DATA_RESET, useCase::reset, String.class);
    }

    @Bean
    public CommandLineRunner commandLineRunner(DynamicRegistry handlerRegistry) {
        return args -> {
            handlerRegistry.startListeningEvent("animals.dogs")
                    .then(handlerRegistry.startListeningEvent("animals.cats"))
                    .then(handlerRegistry.startListeningEvent("animals.cats.angry"))
                    .doOnSuccess(aVoid -> log.info("Listening dynamic events"))
                    .subscribe();
        };
    }
}
