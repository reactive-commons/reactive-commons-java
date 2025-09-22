package org.reactivecommons.async.commons.utils.matcher;

import java.util.Set;

@FunctionalInterface
public interface Matcher {
    String match(Set<String> sources, String target);
}
