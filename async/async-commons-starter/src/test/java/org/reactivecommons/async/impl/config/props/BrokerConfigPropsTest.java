package org.reactivecommons.async.impl.config.props;

import static org.assertj.core.api.Assertions.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

@SpringBootTest
@RunWith(SpringRunner.class)
public class BrokerConfigPropsTest {

    @Autowired
    private BrokerConfigProps config;

    @Test
    public void testConfigDefaults() {
        assertThat(config).extracting(BrokerConfigProps::getDirectMessagesExchangeName, BrokerConfigProps::getDomainEventsExchangeName)
            .containsExactly("directMessages", "domainEvents");
    }

    @Test
    public void testAutoQueueNames() {
        assertThat(config).extracting(BrokerConfigProps::getEventsQueue, BrokerConfigProps::getQueriesQueue)
            .containsExactly("test-app.subsEvents", "test-app.query");
    }

    @Test
    public void testReplyQueue() {
        assertThat(config.getReplyQueue()).startsWith("test-app");
        assertThat(config.getReplyQueue()).isEqualTo(config.getReplyQueue());
    }

    @SpringBootApplication
    static class App {
        public static void main(String[] args) {
            SpringApplication.run(App.class, args);
        }
    }


}