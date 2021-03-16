package org.reactivecommons.async.parent.utils.matcher;

import lombok.Data;

import java.util.Comparator;

@Data
public class Candidate implements Comparable<Candidate>, Comparator<Candidate> {
    private final String key;
    private final long score;

    @Override
    public int compareTo(Candidate o) {
        return (int) (this.score - o.score);
    }

    @Override
    public int compare(Candidate o1, Candidate o2) {
        return (int) (o1.score - o2.score);
    }
}

