package org.reactivecommons.async.starter.config;


import org.junit.jupiter.api.Test;
import org.mockito.Spy;
import org.reactivecommons.async.starter.impl.mybroker.MyBrokerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = {
        MyBrokerConfig.class,
        ReactiveCommonsConfig.class
})
class ReactiveCommonsConfigTest {
    @Spy
    @Autowired
    private ApplicationContext context;

    @Test
    void shouldCreateConnectionManager() {
        // Arrange
        // Act
        ConnectionManager manager = context.getBean(ConnectionManager.class);
        // Assert
        assertNotNull(manager);
    }
}
