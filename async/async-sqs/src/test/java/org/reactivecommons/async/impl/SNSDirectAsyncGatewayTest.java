package org.reactivecommons.async.impl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.impl.sns.communications.Sender;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class SNSDirectAsyncGatewayTest {
  @Mock
  private Sender sender;
  private DirectAsyncGateway directAsyncGateway;
  private String topicTarget;
  private String targetAppName;

  @Before
  public void setup() {
    topicTarget = "topicTarget";
    targetAppName = "targetAppName";
    directAsyncGateway = new SNSDirectAsyncGateway(sender, topicTarget);
  }

  @Test
  public void shouldSendCommand() {
    // Arrange
    when(sender.publish(any(), anyString())).thenReturn(Mono.empty());
    Command<String> command = new Command<>("name", "commandId", "data");
    // Act
    Mono<Void> result = directAsyncGateway.sendCommand(command, targetAppName);
    // Assert
    StepVerifier.create(result)
        .expectComplete()
        .verify();
    verify(sender, times(1)).publish(command, targetAppName + topicTarget);
  }

  @Test
  public void shouldHandleErrorSignalWhenFail() {
    // Arrange
    when(sender.publish(any(), anyString())).thenReturn(Mono.error(new Exception("Unhandled exception")));
    Command<String> command = new Command<>("name", "commandId", "data");
    // Act
    Mono<Void> result = directAsyncGateway.sendCommand(command, targetAppName);
    // Assert
    StepVerifier.create(result)
        .expectError(RuntimeException.class)
        .verify();
    verify(sender, times(1)).publish(command, targetAppName + topicTarget);
  }
}
