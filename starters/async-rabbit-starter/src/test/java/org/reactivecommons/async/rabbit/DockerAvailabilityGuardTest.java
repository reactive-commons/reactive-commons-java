package org.reactivecommons.async.rabbit;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Testcontainers-based tests are annotated with {@code @Testcontainers(disabledWithoutDocker = true)}, so they are
 * silently skipped when Docker is not available. On CI that would hide the integration coverage: this test fails the
 * build instead.
 */
class DockerAvailabilityGuardTest {

    @Test
    void containerTestsMustNotBeSilentlySkippedOnCi() {
        if (!"true".equalsIgnoreCase(System.getProperty("env.ci"))) {
            return; // local development is unaffected
        }
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker must be available on CI: Testcontainers tests would silently skip otherwise")
                .isTrue();
    }
}
