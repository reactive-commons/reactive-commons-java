package org.reactivecommons.async.rabbit;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * The meters of the live senders, listeners and notifiers, per registry. Micrometer returns the meter already
 * registered with the same name and tags, so components with the same tags (e.g. two applications running at the same
 * time on one registry, or one restarted on a registry that outlives it) share their meters:
 * <ul>
 *     <li>a meter is removed from the registry only once no live component uses it anymore, so closing a component
 *     never takes the meters of another one, and the meters of closed components are not published anymore;</li>
 *     <li>a gauge (or function counter) stays bound to the object of the component that registered it first: when
 *     that component closes while others use it, it is registered again, bound to the object of the next one.</li>
 * </ul>
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MeterUsage {

    private static final Map<MeterRegistry, Map<Meter.Id, Deque<Registration>>> USAGES = new WeakHashMap<>();

    private record Registration(Object owner, Supplier<? extends Meter> registration, boolean boundToObjects) {
    }

    /**
     * Registers a meter used by the owner.
     *
     * @param registry     where the meter is registered
     * @param owner        the component that uses it, the one given to {@link #release(MeterRegistry, Object)}
     * @param registration registers the meter, bound to the objects of the owner; called again to bind it to them
     *                     when the component it was bound to closes
     * @return the meter registered with that name and tags, possibly by another owner
     */
    public static <M extends Meter> M register(MeterRegistry registry, Object owner, Supplier<M> registration) {
        synchronized (USAGES) {
            final M meter = registration.get();
            USAGES.computeIfAbsent(registry, ignored -> new HashMap<>())
                    .computeIfAbsent(meter.getId(), ignored -> new ArrayDeque<>())
                    .addLast(new Registration(owner, registration,
                            meter instanceof Gauge || meter instanceof FunctionCounter));
            return meter;
        }
    }

    /**
     * Releases every meter used by the owner: a meter no other owner uses is removed from the registry, and a gauge
     * bound to the owner is bound to the next owner using it.
     *
     * @param registry where the meters were registered
     * @param owner    the component that is closing
     */
    public static void release(MeterRegistry registry, Object owner) {
        synchronized (USAGES) {
            final Map<Meter.Id, Deque<Registration>> usages = USAGES.get(registry);
            if (usages == null) {
                return;
            }
            for (Map.Entry<Meter.Id, Deque<Registration>> usage : new ArrayList<>(usages.entrySet())) {
                final Deque<Registration> registrations = usage.getValue();
                final Registration bound = registrations.peekFirst();
                final boolean rebind = bound != null && bound.owner() == owner && bound.boundToObjects();
                if (!registrations.removeIf(registration -> registration.owner() == owner)) {
                    continue;
                }
                if (registrations.isEmpty()) {
                    usages.remove(usage.getKey());
                    registry.remove(usage.getKey());
                } else if (rebind) {
                    registry.remove(usage.getKey());
                    registrations.peekFirst().registration().get();
                }
            }
            if (usages.isEmpty()) {
                USAGES.remove(registry);
            }
        }
    }
}
