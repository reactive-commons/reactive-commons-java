package org.reactivecommons.async.rabbit.standalone.config;

import lombok.Data;

@Data
public class RabbitProperties {
    private String host = "localhost";
    private int port = 5672;
    private String username = "guest";
    private String password = "guest"; //NOSONAR
    private String virtualHost;
    private Integer channelPoolMaxCacheSize;
}
