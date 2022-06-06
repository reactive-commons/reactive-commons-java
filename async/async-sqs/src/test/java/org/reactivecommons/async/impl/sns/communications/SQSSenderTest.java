package org.reactivecommons.async.impl.sns.communications;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class SQSSenderTest {
  private final String message = "my string message";
  private final int delaySeconds = 0;
  private final String retryCount = "0";
  private final String queueUrl = "queueUrl";
  @Mock
  private SqsAsyncClient client;
  private SQSSender sender;

  @Before
  public void setup() {
    sender = new SQSSender(client);
  }

  @Test
  public void shouldSend() {
    // Arrange
    SendMessageResponse response = SendMessageResponse.builder().messageId("messageId").build();
    when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(CompletableFuture.completedFuture(response));
    ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
    // Act
    Mono<Void> result = sender.send(message, delaySeconds, retryCount, queueUrl);
    // Assert
    StepVerifier.create(result).verifyComplete();
    verify(client).sendMessage(captor.capture());
    SendMessageRequest request = captor.getValue();
    assertThat(request.delaySeconds()).isEqualTo(delaySeconds);
    assertThat(request.queueUrl()).isEqualTo(queueUrl);
    assertThat(request.messageBody()).isEqualTo(message);
    assertThat(request.messageAttributes().get("retries").stringValue()).isEqualTo("1");
  }


  @Test
  public void shouldHandleErrorSignalWhenFail() {
    // Arrange
    CompletableFuture<SendMessageResponse> response = new CompletableFuture<>();
    response.completeExceptionally(new Exception("Unexpected Exception"));
    when(client.sendMessage(any(SendMessageRequest.class))).thenReturn(response);
    // Act
    Mono<Void> result = sender.send(message, delaySeconds, retryCount, queueUrl);
    // Assert
    StepVerifier.create(result).verifyError(Exception.class);
  }
}
