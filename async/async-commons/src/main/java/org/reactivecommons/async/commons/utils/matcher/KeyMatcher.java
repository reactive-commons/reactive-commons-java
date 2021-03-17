package org.reactivecommons.async.commons.utils.matcher;

import lombok.Data;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Set;
import java.util.stream.IntStream;

@Data
public class KeyMatcher implements Matcher {
    private static final String WILD_CARD = "*";
    private static final String SEGMENT_DELIMITER_REGEX = "\\.";

    private String[] getSegments(String s) {
        return s.split(SEGMENT_DELIMITER_REGEX);
    }

    private boolean isBoundedToTarget(String[] current, String[] target) {
        final String lastSegment = current[current.length -1];
        return (current.length == target.length ||
                (current.length < target.length && WILD_CARD.equals(lastSegment)));
    }

    private boolean isRootSegmentCoincident(String[] current, String[] target) {
        final String currentFirstSegment = current[0];
        final String targetFirstSegment = target[0];
        return currentFirstSegment.equalsIgnoreCase(targetFirstSegment);
    }

    private boolean isCandidate(String[] current, String[] target ) {
        return isRootSegmentCoincident(current, target) &&
                isBoundedToTarget(current,target);
    }

    private long calculateMatchingScore(String[] current, String[] target) {
        return IntStream.range(0, Math.min(current.length, target.length))
                .filter(segment -> current[segment].equals(WILD_CARD) ||
                        current[segment].equalsIgnoreCase(target[segment]))
                .count();
    }

    private long getMatchingScore(String[] current, String[] target) {
        final long totalMatches = calculateMatchingScore(current,target);
        final boolean allSegmentsMatched = totalMatches == current.length;
        return allSegmentsMatched ? totalMatches: 0;
    }

    private Candidate getScoredCandidate(Tuple2<String,String[]> candidate, String[] target) {
        final long matchingScore = getMatchingScore(candidate.getT2(), target);
        return new Candidate(candidate.getT1(), matchingScore);
    }

    private String matchMissingKey(Set<String> sources, String target ) {
        final String[] targetSegments = getSegments(target);
        return  sources.stream()
                .map(source -> Tuples.of(source, getSegments(source)))
                .filter(source -> isCandidate(source.getT2(), targetSegments))
                .map(candidate -> getScoredCandidate(candidate, targetSegments))
                .max(Candidate::compareTo)
                .map(Candidate::getKey)
                .orElse(target);
    }

    public String match(Set<String> sources, String target ) {
        return sources.contains(target) || sources.isEmpty() ?
                target :
                matchMissingKey(sources,target);
    }
}
