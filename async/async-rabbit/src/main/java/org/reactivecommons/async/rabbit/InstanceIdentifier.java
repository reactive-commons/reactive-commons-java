package org.reactivecommons.async.rabbit;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class InstanceIdentifier {
    private static final String INSTANCE_ID = UUID.randomUUID().toString().replace("-", "");

    public static String getInstanceId(String kind) {
        return getInstanceId(kind, INSTANCE_ID);
    }

    public static String getInstanceId(String kind, String defaultHost) {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isEmpty()) {
            return defaultHost + "-" + kind;
        }
        return host + "-" + kind;
    }
}
