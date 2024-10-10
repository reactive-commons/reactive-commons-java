package org.reactivecommons.async.starter.mybroker.props;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.reactivecommons.async.starter.props.GenericAsyncProps;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class MyBrokerAsyncProps extends GenericAsyncProps<MyBrokerConnProps> {
    private MyBrokerConnProps connectionProperties;
    @Builder.Default
    private String brokerType = "mybroker";
    private boolean enabled;
    private boolean useDiscardNotifierPerDomain;
}