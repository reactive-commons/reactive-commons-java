package org.reactivecommons.async.rabbit.config;

import lombok.Data;

@Data
public class RabbitProperties {
    private String host = "localhost";
    private int port = 5672;
    private String username = "guest";
    private String password = "guest";
    private String virtualHost;
    private Integer channelPoolMaxCacheSize;
}
