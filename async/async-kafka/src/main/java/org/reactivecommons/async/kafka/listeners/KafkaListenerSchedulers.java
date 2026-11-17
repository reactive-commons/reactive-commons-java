package org.reactivecommons.async.kafka.listeners;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The threads the listeners run on: one pair for all the listeners of a domain, instead of a pair per listener.
 *
 * @param handlers      runs the handlers, their retries and the discard of a message. Its threads are created when
 *                      needed (up to {@link Schedulers#DEFAULT_BOUNDED_ELASTIC_SIZE}) and released once idle, so the
 *                      partitions of every listener can be handled at the same time even when their handlers block
 * @param errorReporter runs the custom error reporter
 */
public record KafkaListenerSchedulers(Scheduler handlers, Scheduler errorReporter) {

    private static final int IDLE_SECONDS = 60;
    private static final int ERROR_REPORTER_THREADS = 4;
    private static final int ERROR_REPORTER_QUEUE = 256;

    /**
     * @param name prefix of the names of the threads
     */
    public static KafkaListenerSchedulers create(String name) {
        return new KafkaListenerSchedulers(
                Schedulers.newBoundedElastic(Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE,
                        Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE, name, IDLE_SECONDS, true),
                Schedulers.newBoundedElastic(ERROR_REPORTER_THREADS, ERROR_REPORTER_QUEUE, name + "-error-reporter",
                        IDLE_SECONDS, true));
    }

    /**
     * Releases the threads; a task still running is interrupted.
     */
    public void dispose() {
        handlers.dispose();
        errorReporter.dispose();
    }
}
