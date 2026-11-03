package org.reactivecommons.async.commons.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Random (version 4) UUIDs, as strong as {@link UUID#randomUUID()} but without its contention: the JDK draws every
 * UUID from a single {@link SecureRandom}. When that is a DRBG, the threads of a busy application queue on its lock:
 * the JDK uses a DRBG by default when {@code java.security.egd} names a source other than {@code file:/dev/random} or
 * {@code file:/dev/urandom}, e.g. the usual {@code -Djava.security.egd=file:/dev/./urandom}. These UUIDs are drawn
 * from one of several instances of the default {@link SecureRandom}, chosen by the thread, so they are as strong and
 * seeded the same way, and the threads rarely wait for each other. With the {@code NativePRNG} of Linux the instances
 * share the source of the operating system, which gains nothing and costs nothing.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RandomUuids {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Created when the first UUID is drawn, like the {@link SecureRandom} of {@link UUID#randomUUID()}.
     */
    private static final class Generators {
        private static final SecureRandom[] INSTANCES = new SecureRandom[16];

        static {
            for (int i = 0; i < INSTANCES.length; i++) {
                INSTANCES[i] = new SecureRandom();
            }
        }
    }

    /**
     * A random UUID, like {@link UUID#randomUUID()}.
     */
    public static UUID next() {
        final byte[] bytes = randomBytes();
        long mostSignificant = 0;
        long leastSignificant = 0;
        for (int i = 0; i < 8; i++) {
            mostSignificant = (mostSignificant << 8) | (bytes[i] & 0xff);
            leastSignificant = (leastSignificant << 8) | (bytes[i + 8] & 0xff);
        }
        return new UUID(mostSignificant, leastSignificant);
    }

    /**
     * A random UUID as 32 lowercase hexadecimal digits: its {@link UUID#toString()} without the dashes.
     */
    public static String nextWithoutDashes() {
        return HEX.formatHex(randomBytes());
    }

    /**
     * The 16 bytes of a random UUID: version 4, IETF variant.
     */
    private static byte[] randomBytes() {
        final SecureRandom[] instances = Generators.INSTANCES;
        final byte[] bytes = new byte[16];
        // each thread keeps using the same instance: the identity hashes of the threads spread them over the instances
        instances[Math.floorMod(System.identityHashCode(Thread.currentThread()), instances.length)].nextBytes(bytes);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        return bytes;
    }
}
