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
 * if no sender was registered (e.g. a custom wiring) it creates its own one, the first time it is needed, and closes
 * it once the last broker provider publishing through it closes (see {@link #retain()} and {@link #release()}).
 */
@RequiredArgsConstructor
public class RabbitMQDiscardProviderImpl implements DiscardProvider {
    private final AsyncProps props;
    private final BrokerConfig config;
    private final MessageConverter converter;
    private final ConnectionFactoryCustomizer cfCustomizer;
    private final AtomicReference<ReactiveMessageSender> defaultSender = new AtomicReference<>();
    private final AtomicReference<DiscardNotifier> delegate = new AtomicReference<>();
    /**
     * Closes the sender this provider created itself, if any, with its connections (guarded by this).
     */
    private Runnable closeOwnSender = () -> {
    };
    private int users; // guarded by this
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

    /**
     * Registers a broker provider that publishes its discards through this provider. Call {@link #release()} when it
     * closes.
     */
    public synchronized void retain() {
        users++;
    }

    /**
     * Unregisters a broker provider. Once none is left, the sender this provider created itself (no sender of the
     * default domain was registered) is closed and its connections are released: nobody publishes through it anymore.
     */
    public synchronized void release() {
        if (users > 0 && --users == 0) {
            closeOwnSender.run();
            closeOwnSender = () -> {
            };
        }
    }

    private DiscardNotifier buildDiscardNotifier() {
        ReactiveMessageSender sender = defaultSender.get();
        if (sender == null) {
            ConnectionFactoryProvider provider = RabbitMQSetupUtils.connectionFactoryProvider(props, cfCustomizer);
            RabbitMQSetupUtils.retainConnections(provider.getConnectionFactory());
            final ReactiveMessageSender own = RabbitMQSetupUtils.createMessageSender(provider, props, converter, null);
            closeOwnSender = () -> {
                own.close();
                RabbitMQSetupUtils.releaseConnections(provider.getConnectionFactory());
            };
            sender = own;
        }
        return RabbitMQSetupUtils.createDiscardNotifier(sender, props, config, converter);
    }
}
