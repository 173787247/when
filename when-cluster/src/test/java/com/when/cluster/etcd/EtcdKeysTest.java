package com.when.cluster.etcd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EtcdKeysTest {
    @Test
    void buildsTheSharedKeySpace() {
        assertEquals("/when/nodes/node-1", EtcdKeys.node("node-1"));
        assertEquals("/when/workers/17", EtcdKeys.worker(17));
        assertEquals("/when/controller", EtcdKeys.CONTROLLER);
        assertEquals("/when/timewheels/tw-1", EtcdKeys.timeWheel("tw-1"));
        assertEquals("/when/config/replica_count", EtcdKeys.config("replica_count"));
    }

    @Test
    void rejectsKeysThatEscapeTheirNamespace() {
        assertThrows(IllegalArgumentException.class, () -> EtcdKeys.node("a/b"));
        assertThrows(IllegalArgumentException.class, () -> EtcdKeys.worker(1024));
    }
}
