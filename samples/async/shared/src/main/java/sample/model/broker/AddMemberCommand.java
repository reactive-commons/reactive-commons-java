package sample.model.broker;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import sample.model.Member;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AddMemberCommand {
    private String teamName;
    private Member member;
}
