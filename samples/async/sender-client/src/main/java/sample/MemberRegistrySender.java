package sample;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import us.sofka.commons.reactive.async.ReplyCommandSender;

@Service
public class MemberRegistrySender extends ReplyCommandSender {
    private static final String REGISTER_MEMBER = "command.register.member";

    public Mono<MemberRegisteredEvent> registerMember(AddMemberCommand command){
        return sendCommand(command, REGISTER_MEMBER, MemberRegisteredEvent.class);
    }

    @Override
    protected String target() {
        return "Receiver2";
    }
}

