package reactor.rabbitmq;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class QueueSpecificationTest {

    @Nested
    class NullName {
        @Test
        void nullNameShouldReturnANonDurableQueue() {
            assertFalse(QueueSpecification.queue().isDurable());
        }

        @Test
        void passingNullNameShouldReturnANonDurableQueue() {
            assertFalse(QueueSpecification.queue(null).isDurable());
        }

        @Test
        void nullNameShouldNotAbleToConfigureDurableToTrue() {
            var spec = QueueSpecification.queue();
            assertThatThrownBy(() -> spec.durable(true))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullNameShouldKeepDurableWhenConfigureDurableToFalse() {
            assertFalse(QueueSpecification.queue().durable(false).isDurable());
        }

        @Test
        void passingNullNameShouldReturnAnExclusiveQueue() {
            assertTrue(QueueSpecification.queue().isExclusive());
        }

        @Test
        void nullNameShouldNotAbleToConfigureExclusiveToFalse() {
            var spec = QueueSpecification.queue();
            assertThatThrownBy(() -> spec.exclusive(false))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullNameShouldKeepExclusiveWhenConfigureExclusiveToTrue() {
            assertTrue(QueueSpecification.queue().exclusive(true).isExclusive());
        }

        @Test
        void passingNullNameShouldReturnAnAutoDeleteQueue() {
            assertTrue(QueueSpecification.queue().isAutoDelete());
        }

        @Test
        void nullNameShouldNotAbleToConfigureAutoDeleteToFalse() {
            var spec = QueueSpecification.queue();
            assertThatThrownBy(() -> spec.autoDelete(false))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nullNameShouldKeepAutoDeleteWhenConfigureAutoDeleteToTrue() {
            assertTrue(QueueSpecification.queue().autoDelete(true).isAutoDelete());
        }

        @Test
        void nullNameShouldNotAbleToConfigurePassiveToTrue() {
            var spec = QueueSpecification.queue();
            assertThatThrownBy(() -> spec.passive(true))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void passingANonNullNameAfterShouldReturnAConfigurableQueueSpecification() {
            var spec = QueueSpecification.queue()
                    .name("not-null-anymore")
                    .durable(true)
                    .exclusive(false)
                    .autoDelete(false)
                    .passive(false);

            assertEquals("not-null-anymore", spec.getName());
            assertTrue(spec.isDurable());
            assertFalse(spec.isAutoDelete());
            assertFalse(spec.isExclusive());
            assertFalse(spec.isPassive());
        }
    }

    @Nested
    class NotNullName {
        @Test
        void queueSpecificationShouldReturnCorrespondingProperties() {
            var spec = QueueSpecification.queue("my-queue")
                    .durable(false)
                    .autoDelete(false)
                    .exclusive(false)
                    .passive(true);

            assertEquals("my-queue", spec.getName());
            assertFalse(spec.isDurable());
            assertFalse(spec.isAutoDelete());
            assertFalse(spec.isExclusive());
            assertTrue(spec.isPassive());
            assertNull(spec.getArguments());
        }

        @Test
        void queueSpecificationShouldReturnCorrespondingPropertiesWhenEmptyName() {
            var spec = QueueSpecification.queue("")
                    .durable(false)
                    .autoDelete(false)
                    .exclusive(false)
                    .passive(false);

            assertEquals("", spec.getName());
            assertFalse(spec.isDurable());
            assertFalse(spec.isAutoDelete());
            assertFalse(spec.isExclusive());
            assertFalse(spec.isPassive());
            assertNull(spec.getArguments());
        }

        @Test
        void queueSpecificationShouldKeepArgumentsWhenNullName() {
            var spec = QueueSpecification.queue("")
                    .arguments(Collections.singletonMap("x-max-length", 1000))
                    .name(null);
            assertNull(spec.getName());
            assertFalse(spec.isDurable());
            assertTrue(spec.isAutoDelete());
            assertTrue(spec.isExclusive());
            assertFalse(spec.isPassive());
            assertThat(spec.getArguments()).isNotNull().hasSize(1).containsKey("x-max-length");
        }
    }
}
