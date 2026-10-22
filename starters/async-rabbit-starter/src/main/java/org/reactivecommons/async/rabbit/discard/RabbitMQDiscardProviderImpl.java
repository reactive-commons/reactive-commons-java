package org.reactivecommons.async.rabbit.discard;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.rabbit.ConnectionFactoryCustomizer;
import org.reactivecommons.async.rabbit.RabbitMQSetupUtils;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Discard provider shared by every domain of the application.
 *
 * <p>It does not own a sender: it publishes through the sender of the default domain (see
 * {@link #useSender(ReactiveMessageSender)}), so notifying discards does not cost extra channels and threads. Only
 * if no sender was registered (e.g. a custom wiring) it creates its own one, the first time it is needed.
 */
@RequiredArgsConstructor
public class RabbitMQDiscardProviderImpl implements DiscardProvider {
    private final AsyncProps props;
    private final BrokerConfig config;
    private final MessageConverter converter;
    private final ConnectionFactoryCustomizer cfCustomizer;
    private final AtomicReference<ReactiveMessageSender> defaultSender = new AtomicReference<>();
    private final AtomicReference<DiscardNotifier> delegate = new AtomicReference<>();
    private final DiscardNotifier lazyNotifier = new DiscardNotifier() {
        @Override
        public Mono<Void> notifyDiscard(Message message) {
            return Mono.defer(() -> resolve().notifyDiscard(message));
        }

        @Override
        public Mono<Void> tryNotifyDiscard(Message message) {
            return Mono.defer(() -> resolve().tryNotifyDiscard(message));
        }
    };

    /**
     * Sets the sender, over the connection of the default domain, used to publish the discarded messages.
     *
     * @param sender the sender of the default domain
     */
    public void useSender(ReactiveMessageSender sender) {
        defaultSender.compareAndSet(null, sender);
    }

    @Override
    public DiscardNotifier get() {
        return lazyNotifier;
    }

    private DiscardNotifier resolve() {
        DiscardNotifier current = delegate.get();
        if (current == null) {
            synchronized (this) {
                current = delegate.get();
                if (current == null) {
                    current = buildDiscardNotifier();
                    delegate.set(current);
                }
            }
        }
        return current;
    }

    private DiscardNotifier buildDiscardNotifier() {
        ReactiveMessageSender sender = defaultSender.get();
        if (sender == null) {
            ConnectionFactoryProvider provider = RabbitMQSetupUtils.connectionFactoryProvider(props, cfCustomizer);
            sender = RabbitMQSetupUtils.createMessageSender(provider, props, converter, null);
        }
        return RabbitMQSetupUtils.createDiscardNotifier(sender, props, config, converter);
    }
}
