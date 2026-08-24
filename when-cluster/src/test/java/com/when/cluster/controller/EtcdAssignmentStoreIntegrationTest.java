package com.when.cluster.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.when.cluster.etcd.EtcdClientConfig;
import com.when.cluster.etcd.EtcdKeys;
import com.when.cluster.etcd.EtcdMetadataClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EtcdAssignmentStoreIntegrationTest {
    private EtcdMetadataClient client;
    private String controllerId;
    private String twId;

    @BeforeEach
    void setUp() throws IOException {
        Map<String, String> environment = configuredEnvironment();
        assumeTrue(environment.containsKey(EtcdClientConfig.ENDPOINTS_ENV));
        client = new EtcdMetadataClient(EtcdClientConfig.fromEnvironment(environment));
        String suffix = UUID.randomUUID().toString();
        controllerId = "controller-" + suffix;
        twId = "controller-tw-" + suffix;
        client.put(EtcdKeys.CONTROLLER, controllerId);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            if (twId != null) {
                client.delete(EtcdKeys.timeWheel(twId));
            }
            client.delete(EtcdKeys.CONTROLLER_PROGRESS);
            client.delete(EtcdKeys.CONTROLLER);
            client.close();
        }
    }

    @Test
    void candidateMigrationRetainsOldSlaveAndOldControllerTermCannotCommit() {
        long term = client.getValue(EtcdKeys.CONTROLLER).orElseThrow().modRevision();
        EtcdAssignmentStore store = new EtcdAssignmentStore(
                controllerId,
                client,
                Clock.fixed(Instant.ofEpochMilli(100_000), ZoneOffset.UTC));

        CommitResult created = store.compareAndSet(new AssignmentDecision(
                "create", term, twId, 0, 1,
                AssignmentAction.CREATE_TIME_WHEEL, "node-1", "node-2"));
        assertTrue(created.committed());

        AssignmentRecord initial = created.assignment().orElseThrow();
        CommitResult candidate = store.compareAndSet(new AssignmentDecision(
                "candidate", term, twId, initial.modRevision(), 2,
                AssignmentAction.ASSIGN_CANDIDATE, "node-2", "node-3"));
        assertTrue(candidate.committed());
        assertEquals("node-2", candidate.assignment().orElseThrow().metadata().slave());
        assertEquals("node-3", candidate.assignment().orElseThrow().metadata().candidateSlave());

        AssignmentRecord rebuilding = candidate.assignment().orElseThrow();
        assertFalse(store.compareAndSet(new AssignmentDecision(
                "too-early", term, twId, rebuilding.modRevision(), 3,
                AssignmentAction.COMPLETE_SLAVE_MOVE, "node-2", "node-3")).committed());

        client.put(EtcdKeys.CONTROLLER, controllerId);
        assertFalse(store.compareAndSet(new AssignmentDecision(
                "old-term", term, twId, rebuilding.modRevision(), 3,
                AssignmentAction.MARK_CANDIDATE_IN_SYNC, "node-2", "node-3")).committed());
    }

    private static Map<String, String> configuredEnvironment() throws IOException {
        Map<String, String> result = new HashMap<>(System.getenv());
        if (result.containsKey(EtcdClientConfig.ENDPOINTS_ENV)) {
            return result;
        }
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."));
        Path runtimeEnvironment = root.resolve(".loop/runtime.env").normalize();
        if (!Files.isRegularFile(runtimeEnvironment)) {
            return result;
        }
        for (String line : Files.readAllLines(runtimeEnvironment)) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                result.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return result;
    }
}
