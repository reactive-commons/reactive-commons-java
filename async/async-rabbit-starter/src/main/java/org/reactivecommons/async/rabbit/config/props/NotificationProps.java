package org.reactivecommons.async.rabbit.config.props;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.utils.NameGenerator;

import java.util.concurrent.atomic.AtomicReference;

@Getter
@RequiredArgsConstructor
public class NotificationProps {

    private final AtomicReference<String> queueName = new AtomicReference<>();
    private final String queueSuffix = "notification";

    public String getQueueName(String applicationName) {
        final String name = this.queueName.get();
        if(name == null) return getGeneratedName(applicationName);
        return name;
    }

    private String getGeneratedName(String applicationName) {
        String generatedName = NameGenerator.generateNameFrom(applicationName, queueSuffix);
        return this.queueName
                .compareAndSet(null, generatedName) ?
                generatedName : this.queueName.get();
    }
}
