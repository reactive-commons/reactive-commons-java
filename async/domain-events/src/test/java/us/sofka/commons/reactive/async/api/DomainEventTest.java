package us.sofka.commons.reactive.async.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.Test;
import org.mockito.junit.MockitoJUnitRunner;

public class DomainEventTest {

    DomainEvent<String> event = new DomainEvent<>("testEvent", "id", "data");

    @Test
    public void getName() {
        assertThat(event.getName()).isEqualTo("testEvent");
    }

    @Test
    public void getEventId() {
        assertThat(event.getEventId()).isEqualTo("id");
    }

    @Test
    public void getData() {
        assertThat(event.getData()).isEqualTo("data");
    }
}