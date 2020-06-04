package org.reactivecommons.async.impl.utils;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

public class NameGenerator {

    public static String generateNameFrom(String applicationName, String suffix) {
        return generateName(applicationName,suffix);
    }

    public static String generateNameFrom(String applicationName) {
        return generateName(applicationName,"");
    }

    private static String generateName(String applicationName, String suffix) {
        UUID uuid = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        // Convert to base64 and remove trailing =
        return applicationName+"-"+ suffix + "-" + encodeToUrlSafeString(bb.array())
                .replace("=", "");
    }

    private static String encodeToUrlSafeString(byte[] src) {
        return new String(encodeUrlSafe(src));
    }

    private static byte[] encodeUrlSafe(byte[] src) {
        if (src.length == 0) {
            return src;
        }
        return Base64.getUrlEncoder().encode(src);
    }
}
