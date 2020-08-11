package org.reactivecommons.async.impl.utils.matcher;

import com.github.javatlacati.contiperf.PerfTest;
import com.github.javatlacati.contiperf.Required;
import com.github.javatlacati.contiperf.junit.ContiPerfRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class KeyMatcherPerformanceTest {
    @Rule
    public ContiPerfRule contiPerfRule = new ContiPerfRule();
    public static AtomicReference<Map<String, String>> candidates = new AtomicReference<>();
    public static AtomicBoolean initialized = new AtomicBoolean(false);
    private KeyMatcher keyMatcher = new KeyMatcher();
    private String DATASET_FILENAME = "candidateNamesForMatching.txt";

    @Before
    public void init() {
        if (!initialized.get()) {
            initialized.set(true);
            try {
                ClassLoader classLoader = KeyMatcherPerformanceTest.class.getClassLoader();
                File file = new File(Objects.requireNonNull(classLoader.getResource(DATASET_FILENAME))
                        .getFile());
                Set<String> names = new HashSet<>(Files
                        .readAllLines(Paths.get(file.getAbsolutePath())));
                candidates.set(names.stream()
                        .collect(Collectors.toMap(name -> name, name -> name)));
            } catch (IOException e) {
                e.printStackTrace();
                candidates.set(new HashMap<>());
            }
        }
    }

    @Test
    @PerfTest(threads = 4, duration = 4000)
    @Required(average = 200, max = 10000, throughput = 200)
    public void getFromMapTest() {
        String existentKey = "System.segment.3073.event.action";
        candidates.get().get(existentKey);
    }

    @Test
    @PerfTest(threads = 4, duration = 4000)
    @Required(average = 200, max = 10000, throughput = 200)
    public void matchNameBeforeTest() {
        keyMatcher.match(candidates.get().keySet(), "System.segment.1.event.action");
    }
}
