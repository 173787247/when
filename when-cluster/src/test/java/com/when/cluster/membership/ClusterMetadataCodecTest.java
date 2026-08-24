package com.when.cluster.membership;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClusterMetadataCodecTest {
    private final ClusterMetadataCodec codec = new ClusterMetadataCodec();

    @Test
    void roundTripsDocumentedNodeFieldsWithoutSensitiveData() {
        NodeInfo node = new NodeInfo("node-1", "127.0.0.1", 8091, 1234, 2.5);

        String json = codec.encodeNode(node);

        assertEquals(node, codec.decodeNode("node-1", json));
        assertEquals(
                "{\"node_id\":\"node-1\",\"ip\":\"127.0.0.1\",\"grpc_port\":8091,"
                        + "\"start_time\":1234,\"load\":2.5}",
                json);
        assertFalse(json.contains("password"));
        assertFalse(json.contains("payload"));
    }

    @Test
    void rejectsAnIdentityThatDoesNotMatchItsEtcdKey() {
        String json = codec.encodeNode(new NodeInfo("node-1", "127.0.0.1", 8091, 1, 0));

        assertThrows(IllegalArgumentException.class, () -> codec.decodeNode("node-2", json));
    }

    @Test
    void acceptsTheLesson42PortFieldDuringTheHandoff() {
        NodeInfo node = codec.decodeNode(
                "node-old",
                "{\"ip\":\"127.0.0.1\",\"port\":8080,\"start_time\":1,\"load\":0}");

        assertEquals(new NodeInfo("node-old", "127.0.0.1", 8080, 1, 0), node);
    }
}
