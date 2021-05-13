package org.reactivecommons.async.commons.utils;

import lombok.extern.java.Log;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.SignalType;

import java.util.logging.Level;

import static org.reactivecommons.async.commons.utils.ArrayUtils.prefixArray;


@Log
public class LoggerSubscriber<T> extends BaseSubscriber<T> {

    private final String flowName;
    private static final String ON_COMPLETE_MSG = "%s: ##On Complete Hook!!";
    private static final String ON_ERROR_MSG = "%s: ##On Error Hook!!";
    private static final String ON_CANCEL_MSG = "%s: ##On Cancel Hook!!";
    private static final String ON_FINALLY_MSG = "%s: ##On Finally Hook! Signal type: %s";

    public LoggerSubscriber(String flowName) {
        this.flowName = flowName;
    }

    @Override
    protected void hookOnComplete() {
        log.warning(format(ON_COMPLETE_MSG));
    }

    @Override
    protected void hookOnError(Throwable throwable) {
        log.log(Level.SEVERE, format(ON_ERROR_MSG), throwable);
    }

    @Override
    protected void hookOnCancel() {
        log.warning(format(ON_CANCEL_MSG));
    }

    @Override
    protected void hookFinally(SignalType type) {
        log.warning(format(ON_FINALLY_MSG, type.name()));
    }

    private String format(String msg, String... args) {
        return String.format(msg, prefixArray(flowName, args));
    }
}
