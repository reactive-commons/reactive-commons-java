package org.reactivecommons.async.commons.utils.matcher;

import org.apache.commons.io.FilenameUtils;

import java.util.Set;

public class KeyMatcher implements Matcher {

    public static final String SEPARATOR_REGEX = "\\.";
    public static final String WILDCARD_CHAR = "*";

    @Override
    public String match(Set<String> sources, String target) {
        return sources.contains(target) || sources.isEmpty() ?
                target :
                matchMissingKey(sources, target);
    }

    private String matchMissingKey(Set<String> names, String target) {
        return names.stream()
                .filter(name -> name.contains(WILDCARD_CHAR))
                .sorted(this::compare)
                .filter(name -> FilenameUtils.wildcardMatch(target, name))
                .findFirst()
                .orElse(target);
    }

    private int compare(String firstExpression, String secondExpression) {
        String[] firstExpressionArr = getSeparated(firstExpression);
        String[] secondExpressionArr = getSeparated(secondExpression);
        for (int i = 0; i < firstExpressionArr.length && i < secondExpressionArr.length; i++) {
            if (firstExpressionArr[i].equals(secondExpressionArr[i])) {
                continue;
            }
            if (firstExpressionArr[i].equals(WILDCARD_CHAR)) {
                return 1;
            }
            return -1;
        }
        return secondExpressionArr.length - firstExpressionArr.length;
    }

    private String[] getSeparated(String expression) {
        return expression.split(SEPARATOR_REGEX);
    }
}
