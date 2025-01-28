package org.reactivecommons.async.commons.utils;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class NameGenerator {

    public static String fromNameWithSuffix(String appName, String suffix) {
        if (suffix != null && !suffix.isEmpty()) {
            return appName + "." + suffix;
        }
        return appName;
    }

    public static String generateNameFrom(String applicationName, String suffix) {
        return generateName(applicationName, suffix);
    }

    public static String generateNameFrom(String applicationName) {
        return generateName(applicationName, "");
    }

    private static String generateName(String applicationName, String suffix) {
        UUID uuid = UUID.randomUUID();
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        // Convert to base64 and remove trailing =
        String realSuffix = suffix != null && !suffix.isEmpty() ? suffix + "." : "";
        return applicationName + "." + realSuffix + encodeToUrlSafeString(bb.array())
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
