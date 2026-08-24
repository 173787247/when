package com.when.ingress.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConsistentHashRouterTest {
    @Test
    void routesTheSameMessageStablyRegardlessOfInputOrder() {
        var first = new ConsistentHashRouter(List.of("tw-c", "tw-a", "tw-b"));
        var second = new ConsistentHashRouter(List.of("tw-b", "tw-c", "tw-a"));
        for (int index = 0; index < 1_000; index++) {
            assertEquals(
                    first.routeToTimeWheel("message-" + index),
                    second.routeToTimeWheel("message-" + index));
        }
    }

    @Test
    void makesEveryConfiguredWheelReachable() {
        var router = new ConsistentHashRouter(List.of("tw-a", "tw-b", "tw-c"));
        Set<String> routed = IntStream.range(0, 5_000)
                .mapToObj(index -> router.routeToTimeWheel("message-" + index))
                .collect(Collectors.toSet());
        assertEquals(Set.of("tw-a", "tw-b", "tw-c"), routed);
        assertTrue(routed.size() > 1);
    }
}
