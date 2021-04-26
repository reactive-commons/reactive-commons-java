package org.reactivecommons.async.commons.utils.matcher;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class KeyMatcherPerformanceManualTest {


    Map<String, String> candidates = new HashMap<>();

    private KeyMatcher keyMatcher = new KeyMatcher();
    private List<String> testList;
    private List<String> testResultList;


    @BeforeEach
    public void init() {
        ClassLoader classLoader = getClass().getClassLoader();
        File file = new File(classLoader.getResource("candidateNamesForMatching.txt").getFile());
        try {
            Set<String> names = new HashSet<>(Files
                    .readAllLines(Paths.get(file.getAbsolutePath())));
            candidates = names.stream()
                    .collect(Collectors.toMap(name -> name, name -> name));
            testList = new ArrayList<>(names);
            testResultList = new ArrayList<>(testList.size() * 10);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void keyMatcherLookupShouldPerformInLessThan1_5Micros() {
        final int size = testList.size();
        final long init = System.currentTimeMillis();
        for (int i = 0; i < size * 10; ++i) {
            testResultList.add(keyMatcher.match(candidates.keySet(), testList.get(i % size)));
        }
        final long end = System.currentTimeMillis();


        final long total = end - init;
        final double microsPerLookup = ((total + 0.0) / testResultList.size()) * 1000;
        System.out.println("Performed Lookups: " + testResultList.size());
        System.out.println("Total Execution Time: " + total + "ms");
        System.out.println("Microseconds per lookup: " + microsPerLookup + "us");
        if (System.getProperty("env.ci") == null) {
            Assertions.assertThat(microsPerLookup).isLessThan(1.5);
        }
    }


}
