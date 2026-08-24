package com.when.cluster.etcd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataJsonCodecTest {
    private final MetadataJsonCodec codec = new MetadataJsonCodec();

    @Test
    void nodeMetadataUsesTheDocumentedFields() {
        NodeMetadata metadata = new NodeMetadata("127.0.0.1", 8080, 1234, 2.5);
        String json = codec.encodeNode(metadata);

        assertEquals(metadata, codec.decodeNode(json));
        assertEquals("{\"ip\":\"127.0.0.1\",\"port\":8080,\"start_time\":1234,\"load\":2.5}", json);
        assertFalse(json.contains("password"));
    }

    @Test
    void timeWheelUsesTheFencedPlacementFields() {
        TimeWheelMetadata metadata =
                new TimeWheelMetadata("node-1", "node-2", "running", "in_sync", 17);
        String json = codec.encodeTimeWheel(metadata);

        assertEquals(metadata, codec.decodeTimeWheel(json));
        assertEquals(
                "{\"master\":\"node-1\",\"slave\":\"node-2\",\"status\":\"running\","
                        + "\"sync_state\":\"in_sync\",\"epoch\":17}",
                json);
        assertThrows(IllegalArgumentException.class,
                () -> new TimeWheelMetadata("node-1", "node-1", "running"));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeWheelMetadata("node-1", "node-2", "running", "in_sync", -1));
        TimeWheelMetadata lesson47Name = codec.decodeTimeWheel(
                "{\"master\":\"node-1\",\"slave\":\"node-2\",\"status\":\"running\","
                        + "\"sync_state\":\"in_sync\",\"assignment_version\":18}");
        assertEquals(18, lesson47Name.assignmentVersion());
    }
}
