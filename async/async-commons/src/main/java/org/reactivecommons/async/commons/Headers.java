package org.reactivecommons.async.commons;


import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Headers {

    public static final String REPLY_ID = "x-reply_id";
    public static final String CORRELATION_ID = "x-correlation-id";
    public static final String COMPLETION_ONLY_SIGNAL = "x-empty-completion";
    public static final String SERVED_QUERY_ID = "x-serveQuery-id";
    public static final String SOURCE_APPLICATION = "sourceApplication";
    public static final String REPLY_TIMEOUT_MILLIS = "x-reply-timeout-millis";
}
