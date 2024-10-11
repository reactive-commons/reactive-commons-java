package org.reactivecommons.async.starter.broker;

import org.reactivecommons.async.commons.DiscardNotifier;

import java.util.function.Supplier;

public interface DiscardProvider extends Supplier<DiscardNotifier> {
}
