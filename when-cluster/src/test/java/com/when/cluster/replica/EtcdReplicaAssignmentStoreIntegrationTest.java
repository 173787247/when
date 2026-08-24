package com.when.cluster.replica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.when.cluster.etcd.EtcdClientConfig;
import com.when.cluster.etcd.EtcdKeys;
import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.etcd.MetadataJsonCodec;
import com.when.cluster.etcd.TimeWheelMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EtcdReplicaAssignmentStoreIntegrationTest {
    private EtcdMetadataClient client;
    private EtcdReplicaAssignmentStore store;
    private String wheelId;

    @BeforeEach
    void setUp() throws IOException {
        Map<String, String> environment = configuredEnvironment();
        assumeTrue(environment.containsKey(EtcdClientConfig.ENDPOINTS_ENV));
        client = new EtcdMetadataClient(EtcdClientConfig.fromEnvironment(environment));
        wheelId = "replica-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
        if (client != null) {
            if (wheelId != null) {
                client.delete(EtcdKeys.timeWheel(wheelId));
            }
            client.close();
        }
    }

    @Test
    void cachesAssignmentsAndConditionallyMarksCurrentVersionOutOfSync() throws Exception {
        client.put(
                EtcdKeys.timeWheel(wheelId),
                new MetadataJsonCodec().encodeTimeWheel(
                        new TimeWheelMetadata("node-1", "node-2", "running", "in_sync", 7)));
        store = new EtcdReplicaAssignmentStore(client);

        assertEquals(7, store.current(wheelId).orElseThrow().assignmentVersion());
        assertTrue(store.markSyncState(wheelId, 7, "out_of_sync"));
        await(() -> store.current(wheelId)
                        .map(value -> value.syncState().equals("out_of_sync"))
                        .orElse(false),
                Duration.ofSeconds(3));
        assertTrue(!store.markSyncState(wheelId, 6, "in_sync"));
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
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
