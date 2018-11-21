package sample;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class MemberRegisteredEvent {
    private String memberId;
    private Integer initialScore;
}
