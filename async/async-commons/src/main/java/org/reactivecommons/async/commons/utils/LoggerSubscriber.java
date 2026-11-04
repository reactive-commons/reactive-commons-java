package org.reactivecommons.async.commons.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.SignalType;


/**
 * Logs how a flow that should never end terminates: its completion at {@code WARN} and its error at {@code ERROR}.
 * A cancellation is the application stopping the flow (e.g. while it shuts down, or to restart it), so it is logged
 * at {@code DEBUG}, like the final signal, which only repeats one of the others.
 */
@Slf4j
@RequiredArgsConstructor
public class LoggerSubscriber<T> extends BaseSubscriber<T> {

    private final String flowName;
    private static final String ON_COMPLETE_MSG = "{}: ##On Complete Hook!!";
    private static final String ON_ERROR_MSG = "{}: ##On Error Hook!! {}";
    private static final String ON_CANCEL_MSG = "{}: ##On Cancel Hook!!";
    private static final String ON_FINALLY_MSG = "{}: ##On Finally Hook! Signal type: {}";

    @Override
    protected void hookOnComplete() {
        log.warn(ON_COMPLETE_MSG, flowName);
    }

    @Override
    protected void hookOnError(@NonNull Throwable throwable) {
        log.error(ON_ERROR_MSG, flowName, throwable.getMessage(), throwable);
    }

    @Override
    protected void hookOnCancel() {
        log.debug(ON_CANCEL_MSG, flowName);
    }

    @Override
    protected void hookFinally(SignalType type) {
        log.debug(ON_FINALLY_MSG, flowName, type.name());
    }
}
