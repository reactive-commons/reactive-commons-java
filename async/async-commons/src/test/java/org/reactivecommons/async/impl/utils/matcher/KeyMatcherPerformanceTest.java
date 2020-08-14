package org.reactivecommons.async.impl.utils.matcher;

import org.databene.contiperf.PerfTest;
import org.databene.contiperf.Required;
import org.databene.contiperf.junit.ContiPerfRule;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ContextConfiguration(classes = KeyMatcher.class)
@RunWith(SpringJUnit4ClassRunner.class)
public class KeyMatcherPerformanceTest {

    @Rule
    public ContiPerfRule contiPerfRule = new ContiPerfRule();
    static Map<String, String> candidates = new HashMap<>();

    private KeyMatcher keyMatcher = new KeyMatcher();


    @BeforeClass
    public static void init() {
        ClassLoader classLoader = KeyMatcherPerformanceTest.class.getClassLoader();
        File file = new File(classLoader.getResource("candidateNamesForMatching.txt").getFile());
        try {
             Set<String> names =  new HashSet<>(Files
                    .readAllLines(Paths.get(file.getAbsolutePath())));
            candidates = names.stream()
                    .collect(Collectors.toMap(name -> name, name -> name));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    @PerfTest(threads = 4, duration = 4000)
    @Required(average = 200, max = 10000, throughput = 200)
    public void getFromMapTest() {
        String existentKey = "System.segment.3073.event.action";
        candidates.get(existentKey);
    }


    @Test
    @PerfTest(threads = 4, duration = 4000)
    @Required(average = 200, max = 10000, throughput = 200)
    public void matchNameBeforeTest() {
        keyMatcher.match(candidates.keySet(), "System.segment.1.event.action");
    }
}
