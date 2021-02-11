package org.reactivecommons.async.api;

import org.junit.jupiter.api.Test;
import org.reactivecommons.api.domain.DomainEvent;

import static org.assertj.core.api.Assertions.assertThat;

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