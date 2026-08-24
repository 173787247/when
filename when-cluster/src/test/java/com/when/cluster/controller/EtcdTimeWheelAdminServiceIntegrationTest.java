package com.when.cluster.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.when.cluster.etcd.EtcdClientConfig;
import com.when.cluster.etcd.EtcdKeys;
import com.when.cluster.etcd.EtcdMetadataClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EtcdTimeWheelAdminServiceIntegrationTest {
    private EtcdMetadataClient client;

    @BeforeEach
    void setUp() {
        assumeTrue(System.getenv(EtcdClientConfig.ENDPOINTS_ENV) != null);
        client = EtcdMetadataClient.fromEnvironment();
        clearOperations();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            clearOperations();
            client.close();
        }
    }

    @Test
    void completedResultReplaysAcrossServiceInstanceAndDifferentBodyConflicts() {
        AtomicInteger creates = new AtomicInteger();
        Controller controller = new Controller() {
            @Override public void onControllerElected(ControllerTerm term) { }
            @Override public void onClusterEvent(ClusterEvent event) { }
            @Override public TimeWheelAssignmentView createTimeWheel(TimeWheelSpec spec) {
                creates.incrementAndGet();
                return new TimeWheelAssignmentView(
                        spec.twId(), "node-a", "node-b", "running", "in_sync", 1);
            }
        };

        var first = new EtcdTimeWheelAdminService(controller, client).create(2, "stable-key");
        var replay = new EtcdTimeWheelAdminService(controller, client).create(2, "stable-key");

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.assignments(), replay.assignments());
        assertEquals(2, creates.get());
        assertThrows(TimeWheelAdminService.IdempotencyConflictException.class,
                () -> new EtcdTimeWheelAdminService(controller, client).create(3, "stable-key"));
    }

    private void clearOperations() {
        client.getPrefix(EtcdKeys.TIME_WHEEL_IDEMPOTENCY_PREFIX).keySet().forEach(client::delete);
    }
}
