package org.reactivecommons.async.rabbit.config;

import org.reactivecommons.async.rabbit.HandlerResolver;

import java.util.Map;
import java.util.TreeMap;

public class DomainHandlers {
    private final Map<String, HandlerResolver> handlers = new TreeMap<>();

    public void add(String domain, HandlerResolver resolver) {
        this.handlers.put(domain, resolver);
    }

    public HandlerResolver get(String domain) {
        HandlerResolver handlerResolver = handlers.get(domain);
        if (handlerResolver == null) {
            throw new RuntimeException("You are trying to use the domain " + domain
                    + " but this connection is not defined");
        }
        return handlerResolver;
    }
}
