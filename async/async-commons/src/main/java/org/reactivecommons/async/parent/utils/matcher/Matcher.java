package org.reactivecommons.async.parent.utils.matcher;

import java.util.Set;

public interface Matcher {
    String match(Set<String> sources, String target);
}
