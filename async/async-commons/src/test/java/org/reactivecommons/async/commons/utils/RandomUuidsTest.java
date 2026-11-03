package org.reactivecommons.async.commons.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RandomUuidsTest {

    @Test
    void aUuidIsRandomAsTheJdkOnes() {
        for (int i = 0; i < 1_000; i++) {
            UUID uuid = RandomUuids.next();

            assertThat(uuid.version()).as("version: random").isEqualTo(4);
            assertThat(uuid.variant()).as("variant: IETF").isEqualTo(2);
            assertThat(UUID.fromString(uuid.toString())).isEqualTo(uuid);
        }
    }

    @Test
    void aUuidWithoutDashesIsItsTextWithoutTheDashes() {
        for (int i = 0; i < 1_000; i++) {
            String id = RandomUuids.nextWithoutDashes();

            assertThat(id).hasSize(32).matches("[0-9a-f]{12}4[0-9a-f]{3}[89ab][0-9a-f]{15}");
            UUID uuid = UUID.fromString(id.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
            assertThat(uuid.toString().replace("-", "")).isEqualTo(id);
        }
    }

    @Test
    void theUuidsDrawnByManyThreadsAtOnceAreUnique() throws Exception {
        int threads = 8;
        int perThread = 25_000;
        Set<UUID> uuids = ConcurrentHashMap.newKeySet();
        Set<String> withoutDashes = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> drawn = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                drawn.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        uuids.add(RandomUuids.next());
                        withoutDashes.add(RandomUuids.nextWithoutDashes());
                    }
                }));
            }
            for (Future<?> future : drawn) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(uuids).hasSize(threads * perThread);
        assertThat(withoutDashes).hasSize(threads * perThread);
    }
}
