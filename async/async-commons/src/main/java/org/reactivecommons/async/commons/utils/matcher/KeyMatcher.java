package org.reactivecommons.async.commons.utils.matcher;

import java.util.Set;
import java.util.regex.Pattern;

public class KeyMatcher implements Matcher {

    public static final String SEPARATOR_REGEX = "\\.";
    public static final String SINGLE_WORD_WILDCARD = "*";
    public static final String MULTIPLE_WORD_WILDCARD = "#";

    @Override
    public String match(Set<String> sources, String target) {
        return sources.contains(target) || sources.isEmpty() ?
                target :
                matchMissingKey(sources, target);
    }

    public static String matchMissingKey(Set<String> names, String target) {
        return names.stream()
                .filter(name -> matches(target, name))
                .min(KeyMatcher::compare)
                .orElse(target);
    }

    private static int compare(String firstExpression, String secondExpression) {
        String[] firstExpressionArr = getSeparated(firstExpression);
        String[] secondExpressionArr = getSeparated(secondExpression);
        return compare(secondExpressionArr.length - firstExpressionArr.length, firstExpressionArr, secondExpressionArr, 0);
    }

    private static int compare(int current, String[] first, String[] second, int idx) {
        if (idx >= first.length || idx >= second.length) {
            return current;
        }
        if (first[idx].equals(second[idx])) {
            return compare(current, first, second, idx + 1);
        }
        if (!first[idx].equals(SINGLE_WORD_WILDCARD) && !first[idx].equals(MULTIPLE_WORD_WILDCARD)) {
            return -1;
        }
        if (!second[idx].equals(SINGLE_WORD_WILDCARD) && !second[idx].equals(MULTIPLE_WORD_WILDCARD)) {
            return 1;
        }
        if (first[idx].equals(MULTIPLE_WORD_WILDCARD) && second[idx].equals(SINGLE_WORD_WILDCARD)) {
            return compare(1, first, second, idx + 1);
        }
        if (first[idx].equals(SINGLE_WORD_WILDCARD) && second[idx].equals(MULTIPLE_WORD_WILDCARD)) {
            return compare(-1, first, second, idx + 1);
        }
        return second.length - first.length;
    }

    private static boolean matches(String routingKey, String pattern) {
        if (!pattern.contains(SINGLE_WORD_WILDCARD) && !pattern.contains(MULTIPLE_WORD_WILDCARD)) {
            return false;
        }
        // Convert RabbitMQ wildcard pattern to regex pattern
        String regexPattern = pattern.replace(".", "\\.")
                .replace("*", "[^.]+")
                .replace("#", ".*");

        // Compile regex pattern
        Pattern p = Pattern.compile("^" + regexPattern + "$");

        // Match routing key against regex pattern
        java.util.regex.Matcher m = p.matcher(routingKey);

        return m.matches();
    }

    private static String[] getSeparated(String expression) {
        return expression.split(SEPARATOR_REGEX);
    }
}
