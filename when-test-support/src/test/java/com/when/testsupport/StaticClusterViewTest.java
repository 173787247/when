package com.when.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.when.core.NodeEndpoint;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StaticClusterViewTest {
    @Test
    void identicalStaticMapsResolveIdenticalMastersOnEveryNode() {
        Map<String, NodeEndpoint> placement = Map.of(
                "tw-0", new NodeEndpoint("node-1", "127.0.0.1", 9001),
                "tw-1", new NodeEndpoint("node-2", "127.0.0.1", 9002));
        var first = new StaticClusterView(placement);
        var second = new StaticClusterView(placement);
        assertEquals(first.masterOf("tw-0"), second.masterOf("tw-0"));
        assertEquals(first.masterOf("tw-1"), second.masterOf("tw-1"));
        assertTrue(first.masterOf("missing").isEmpty());
    }
}
