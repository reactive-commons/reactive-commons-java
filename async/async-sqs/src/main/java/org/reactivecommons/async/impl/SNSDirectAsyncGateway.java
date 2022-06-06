package org.reactivecommons.async.impl;

import lombok.AllArgsConstructor;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.api.From;
import org.reactivecommons.async.impl.sns.communications.Sender;
import reactor.core.publisher.Mono;

@AllArgsConstructor
public class SNSDirectAsyncGateway implements DirectAsyncGateway {

  private Sender sender;
  private String topicTarget;

  @Override
  public <T> Mono<Void> sendCommand(Command<T> command, String targetAppName) {
    return sender.publish(command, getTargetTopic(targetAppName))
            .onErrorMap(err -> new RuntimeException("Command send failure: " + command.getName() + " Reason: "+ err.getMessage(), err));
  }

  @Override
  public <T, R> Mono<R> requestReply(AsyncQuery<T> query, String targetName, Class<R> type) {
    return null;
  }

  @Override
  public <T> Mono<Void> reply(T response, From from) {
    return null;
  }

  private String getTargetTopic(String targetAppName) {
    return targetAppName.concat(topicTarget);
  }
}
