package com.when.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ManagementHttpServerTest {
    @Test
    void separatesLivenessConfigurationFromDependencyAwareReadiness() {
        ReadinessManager readiness = new ReadinessManager();
        ReadinessSnapshot unavailable = readiness.snapshot();
        assertFalse(unavailable.ready());
        assertTrue(unavailable.reasons().contains(ReadinessReason.REDIS_UNAVAILABLE));
        assertTrue(unavailable.reasons().contains(ReadinessReason.ETCD_UNAVAILABLE));

        readiness.initialized(true);
        readiness.redisAvailable(true);
        readiness.etcdAvailable(true);
        readiness.registered(true);
        readiness.rolesRecovered(true);
        assertTrue(readiness.snapshot().ready());

        ManagementConfig config = new ManagementConfig("127.0.0.1", 0, false);
        assertEquals("127.0.0.1", config.host());
        assertFalse(config.runtimeLogLevelEnabled());
    }

}
