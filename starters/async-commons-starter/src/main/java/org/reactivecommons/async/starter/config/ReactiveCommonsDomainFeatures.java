package org.reactivecommons.async.starter.config;

import java.util.HashMap;


public class ReactiveCommonsDomainFeatures extends HashMap<String, ReactiveCommonsFeatures> {

    public ReactiveCommonsDomainFeatures() {
        super();
    }

    public ReactiveCommonsFeatures ofDomain(String key) {
        return this.get(key);
    }

    public ReactiveCommonsFeatures withDomain(String key) {
        return this.computeIfAbsent(key, k -> new ReactiveCommonsFeatures());
    }
}
