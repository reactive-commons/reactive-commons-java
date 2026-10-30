package org.reactivecommons.async.commons.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.SignalType;


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
    protected void hookOnError(Throwable throwable) {
        log.error(ON_ERROR_MSG, flowName, throwable.getMessage(), throwable);
    }

    @Override
    protected void hookOnCancel() {
        log.warn(ON_CANCEL_MSG, flowName);
    }

    @Override
    protected void hookFinally(SignalType type) {
        log.warn(ON_FINALLY_MSG, flowName, type.name());
    }
}
