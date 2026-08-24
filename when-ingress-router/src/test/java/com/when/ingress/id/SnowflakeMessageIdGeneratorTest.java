package com.when.ingress.id;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SnowflakeMessageIdGeneratorTest {
    @Test
    void generatesUniqueIdsConcurrently() throws Exception {
        var generator = new SnowflakeMessageIdGenerator(17);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        var executor = Executors.newFixedThreadPool(8);
        try {
            for (int index = 0; index < 20_000; index++) {
                executor.submit(() -> ids.add(generator.nextId()));
            }
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(20_000, ids.size());
    }
}
