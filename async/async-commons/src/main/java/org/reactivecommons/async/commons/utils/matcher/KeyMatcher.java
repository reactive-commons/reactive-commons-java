package org.reactivecommons.async.commons.utils.matcher;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class KeyMatcher implements Matcher {

    public static final String SEPARATOR_REGEX = "\\.";
    public static final String SINGLE_WORD_WILDCARD = "*";
    public static final String MULTIPLE_WORD_WILDCARD = "#";

    /**
     * The regular expressions of the patterns with wildcards, compiled once: the patterns are the names of the
     * registered handlers, which every lookup without an exact match tries.
     */
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

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
        if (!isWildcard(first[idx])) {
            return -1;
        }
        if (!isWildcard(second[idx])) {
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

    private static boolean isWildcard(String word) {
        return word.equals(SINGLE_WORD_WILDCARD) || word.equals(MULTIPLE_WORD_WILDCARD);
    }

    private static boolean matches(String routingKey, String pattern) {
        if (!pattern.contains(SINGLE_WORD_WILDCARD) && !pattern.contains(MULTIPLE_WORD_WILDCARD)) {
            return false;
        }
        return PATTERNS.computeIfAbsent(pattern, KeyMatcher::compile).matcher(routingKey).matches();
    }

    /**
     * The RabbitMQ wildcards of the pattern as a regular expression: {@code *} is exactly one word, {@code #} any
     * number of words.
     */
    private static Pattern compile(String pattern) {
        String regexPattern = pattern.replace(".", "\\.")
                .replace("*", "[^.]+")
                .replace("#", ".*");
        return Pattern.compile("^" + regexPattern + "$");
    }

    private static String[] getSeparated(String expression) {
        return expression.split(SEPARATOR_REGEX);
    }
}
