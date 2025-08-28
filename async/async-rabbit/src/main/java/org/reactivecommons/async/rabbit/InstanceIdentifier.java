package org.reactivecommons.async.rabbit;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class InstanceIdentifier {
    public static String getInstanceId(String kind) {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isEmpty()) {
            return kind;
        }
        return host + "-" + kind;
    }
}
