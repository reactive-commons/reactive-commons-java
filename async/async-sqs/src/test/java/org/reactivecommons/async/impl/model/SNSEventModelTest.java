package org.reactivecommons.async.impl.model;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SNSEventModelTest {

  @Test
  public void shouldParseTimestamp() {
    // Arrange
    String timestampStr = "2020-09-21T14:39:01.962-05:00";
    long timestamp = 1600717141962L;
    SNSEventModel model = new SNSEventModel();
    // Act
    model.setTimestamp(timestampStr);
    // Assert
    assertThat(model.getTimestamp().getMillis()).isEqualTo(timestamp);
  }
}
