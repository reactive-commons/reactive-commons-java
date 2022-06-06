package org.reactivecommons.async.impl.sns.communications;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.async.impl.Headers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class SenderTest {

  private final String sourceApp = "myAppName";
  private final String arnPrefix = "arnPrefix";
  @Mock
  private SnsAsyncClient client;
  private Sender sender;

  @Before
  public void setup() {
    sender = new Sender(client, sourceApp, arnPrefix);
  }

  @Test
  public void shouldSend() {
    // Arrange
    ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
    PublishResponse response = PublishResponse.builder().messageId("messageId").build();
    when(client.publish(any(PublishRequest.class))).thenReturn(CompletableFuture.completedFuture(response));
    String targetName = "targetName";
    Object message = getMessage();
    String jsonMessage = "{\"a\":1,\"b\":\"dos\"}";
    // Act
    Mono<Void> result = sender.publish(message, targetName);
    // Assert
    StepVerifier.create(result).verifyComplete();
    verify(client).publish(captor.capture());
    PublishRequest request = captor.getValue();
    assertThat(request.message()).isEqualTo(jsonMessage);
    assertThat(request.topicArn()).isEqualTo(arnPrefix + ":" + targetName);
    assertThat(request.messageAttributes().get(Headers.SOURCE_APPLICATION).stringValue()).isEqualTo(sourceApp);
  }

  @Test
  public void shouldHandleErrorSignalWhenFail() {
    // Arrange
    String targetName = "targetName";
    ClassThatJacksonCannotSerialize message = new ClassThatJacksonCannotSerialize();
    // Act
    Mono<Void> result = sender.publish(message, targetName);
    // Assert
    StepVerifier.create(result)
        .verifyError(JsonProcessingException.class);
  }

  private Object getMessage() {
    Map<String, Object> message = new HashMap<>();
    message.put("a", 1);
    message.put("b", "dos");
    return message;
  }

  private static class ClassThatJacksonCannotSerialize {
    private final ClassThatJacksonCannotSerialize self = this;

    @Override
    public String toString() {
      return self.getClass().getName();
    }
  }
}
