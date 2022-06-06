package org.reactivecommons.async.impl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.impl.sns.communications.Sender;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class SNSDomainEventBusTest {
  @Mock
  private Sender sender;
  private DomainEventBus domainEventBus;
  private String topicName;

  @Before
  public void setup() {
    topicName = "topicName";
    domainEventBus = new SNSDomainEventBus(sender, topicName);
  }

  @Test
  public void shouldEmit() {
    // Arrange
    when(sender.publish(any(), anyString())).thenReturn(Mono.empty());
    DomainEvent<String> event = new DomainEvent<>("name", "eventId", "data");
    // Act
    Publisher<Void> result = domainEventBus.emit(event);
    // Assert
    StepVerifier.create(result)
        .expectComplete()
        .verify();
    verify(sender, times(1)).publish(event, topicName);
  }

  @Test
  public void shouldHandleErrorSignalWhenFail() {
    // Arrange
    when(sender.publish(any(), anyString())).thenReturn(Mono.error(new Exception("Unhandled exception")));
    DomainEvent<String> event = new DomainEvent<>("name", "eventId", "data");
    // Act
    Publisher<Void> result = domainEventBus.emit(event);
    // Assert
    StepVerifier.create(result)
        .expectError(RuntimeException.class)
        .verify();
    verify(sender, times(1)).publish(event, topicName);
  }
}
