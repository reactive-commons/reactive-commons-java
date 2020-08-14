package org.reactivecommons.test;

import lombok.Data;
import java.util.concurrent.ThreadLocalRandom;

@Data
class DummyMessage {
    private String name = "Daniel" + ThreadLocalRandom.current().nextLong();
    private Long age = ThreadLocalRandom.current().nextLong();
    private String field1 = "Field Data " + ThreadLocalRandom.current().nextLong();
    private String field2 = "Field Data " + ThreadLocalRandom.current().nextLong();
    private String field3 = "Field Data " + ThreadLocalRandom.current().nextLong();
    private String field4 = "Field Data " + ThreadLocalRandom.current().nextLong();
}
