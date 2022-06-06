package org.reactivecommons.async.impl.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import lombok.Data;
import org.joda.time.DateTime;

@Data
public class SNSEventModel {

  private static final long serialVersionUID = -7038894618736475592L;

  @JsonProperty("MessageAttributes")
  private Map<String, MessageAttributeModel> messageAttributes;

  @JsonProperty("SigningCertURL")
  private String signingCertUrl;

  @JsonProperty("MessageId")
  private String messageId;

  @JsonProperty("Message")
  private String message;

  @JsonProperty("Subject")
  private String subject;

  @JsonProperty("UnsubscribeURL")
  private String unsubscribeUrl;

  @JsonProperty("Type")
  private String type;

  @JsonProperty("SignatureVersion")
  private String signatureVersion;

  @JsonProperty("Signature")
  private String signature;

  @JsonProperty("Timestamp")
  private DateTime timestamp;

  @JsonProperty("TopicArn")
  private String topicArn;

  public void setTimestamp(String timestamp) {
    DateTime dt = new DateTime(timestamp);
    this.timestamp = dt;
  }
}

@Data
class MessageAttributeModel {
  private static final long serialVersionUID = -5656179310535967619L;

  @JsonProperty("Type")
  private String type;

  @JsonProperty("Value")
  private String value;
}
