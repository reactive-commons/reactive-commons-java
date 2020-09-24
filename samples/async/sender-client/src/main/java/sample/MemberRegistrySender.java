package sample;

import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class MemberRegistrySender  {
    private static final String REGISTER_MEMBER = "serveQuery.register.member";

    @Autowired
    private DirectAsyncGateway asyncGateway;

    public Mono<MemberRegisteredEvent> registerMember(AddMemberCommand command){
        AsyncQuery<AddMemberCommand> asyncQuery = new AsyncQuery<>(REGISTER_MEMBER, command);
        return asyncGateway.requestReply(asyncQuery, target(), MemberRegisteredEvent.class);
    }

    protected String target() {
        return "Receiver2";
    }
}

