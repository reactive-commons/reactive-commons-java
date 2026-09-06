package org.reactivecommons.async.kafka.apicurio;

import org.apache.kafka.common.header.Headers;
import org.reactivecommons.async.kafka.validation.NoOpSchemaValidator;
import org.reactivecommons.async.kafka.validation.SchemaValidator;

import java.util.Map;

/**
 * {@link SchemaValidator} that applies the validator configured for the topic of each record.
 * <p>
 * This is what segments the Apicurio configuration per topic: every topic listed under
 * {@code apicurio.registries[].topics[]} has its own validator, with its own registry, group and artifact, and a
 * topic that is not listed is not validated at all.
 * <p>
 * The validators are built once at startup, so routing a record is a single map lookup.
 * <p>
 * The registry connections are not owned here: they belong to the {@link SharedSchemaResolvers} the validators
 * were built with, which is what releases them.
 */
public class TopicSchemaValidatorRouter implements SchemaValidator {

    private final Map<String, SchemaValidator> byTopic;

    public TopicSchemaValidatorRouter(Map<String, SchemaValidator> byTopic) {
        this.byTopic = Map.copyOf(byTopic);
    }

    @Override
    public void validateOutbound(String topic, byte[] payload, Headers headers) {
        forTopic(topic).validateOutbound(topic, payload, headers);
    }

    @Override
    public void validateInbound(String topic, byte[] payload, Headers headers) {
        forTopic(topic).validateInbound(topic, payload, headers);
    }

    /**
     * @param topic name of the topic of the record
     * @return the validator configured for that topic, or {@link NoOpSchemaValidator} when the topic is not
     * declared, which leaves it unvalidated
     */
    public SchemaValidator forTopic(String topic) {
        return byTopic.getOrDefault(topic, NoOpSchemaValidator.INSTANCE);
    }

    /**
     * @return the topics that are validated, that is the ones declared in the configuration
     */
    public Map<String, SchemaValidator> validatedTopics() {
        return byTopic;
    }
}
