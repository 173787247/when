package com.when.cluster.view;

import com.when.cluster.etcd.EtcdClientConfig;
import com.when.cluster.etcd.EtcdKeys;
import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.etcd.MetadataJsonCodec;
import com.when.cluster.etcd.TimeWheelMetadata;
import com.when.cluster.membership.EtcdClusterMembership;
import com.when.cluster.membership.NodeInfo;
import com.when.core.NodeEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EtcdClusterViewIntegrationTest {
    private Map<String, String> environment;
    private EtcdMetadataClient metadata;
    private EtcdMetadataClient masterClient;
    private EtcdClusterMembership masterMembership;
    private EtcdClusterView view;
    private String suffix;

    @BeforeEach
    void setUp() throws IOException {
        environment = configuredEnvironment();
        assumeTrue(environment.containsKey(EtcdClientConfig.ENDPOINTS_ENV));
        suffix = UUID.randomUUID().toString();
        metadata = client();
        masterClient = client();
        masterMembership = new EtcdClusterMembership(masterClient);
    }

    @AfterEach
    void tearDown() {
        if (view != null) {
            view.close();
        }
        if (masterMembership != null) {
            masterMembership.close();
        }
        if (masterClient != null) {
            masterClient.close();
        }
        if (metadata != null) {
            metadata.close();
        }
    }

    @Test
    void resolvesOnlyTheConfiguredOnlineMasterAndTracksWatchUpdates() throws Exception {
        NodeInfo master = new NodeInfo("master-" + suffix, "127.0.0.1", 8123, 1, 0);
        String slaveId = "slave-" + suffix;
        String wheelId = "tw-" + suffix;
        masterMembership.registerSelf(master, 740);
        metadata.put(
                EtcdKeys.timeWheel(wheelId),
                new MetadataJsonCodec().encodeTimeWheel(
                        new TimeWheelMetadata(master.nodeId(), slaveId, "running")));
        view = new EtcdClusterView(metadata);

        assertEquals(new NodeEndpoint(master.nodeId(), master.ip(), master.grpcPort()),
                view.masterOf(wheelId).orElseThrow());
        assertTrue(view.masterOf("unknown-" + suffix).isEmpty());

        masterMembership.deregisterSelf();
        await(() -> view.masterOf(wheelId).isEmpty(), Duration.ofSeconds(3));

        metadata.delete(EtcdKeys.timeWheel(wheelId));
        await(() -> !view.timeWheels().containsKey(wheelId), Duration.ofSeconds(3));
    }

    private EtcdMetadataClient client() {
        return new EtcdMetadataClient(EtcdClientConfig.fromEnvironment(environment));
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
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
