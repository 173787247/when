package com.when.cluster.etcd;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EtcdMetadataClientIntegrationTest {
    private EtcdMetadataClient client;
    private String prefix;

    @BeforeEach
    void setUp() throws IOException {
        Map<String, String> environment = configuredEnvironment();
        assumeTrue(environment.containsKey(EtcdClientConfig.ENDPOINTS_ENV));
        client = new EtcdMetadataClient(EtcdClientConfig.fromEnvironment(environment));
        prefix = "/when/test/lesson42/" + UUID.randomUUID() + "/";
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void putGetPrefixAndPutIfAbsentWork() {
        client.put(prefix + "a", "one");
        client.put(prefix + "b", "two");

        assertEquals("one", client.get(prefix + "a").orElseThrow());
        assertEquals(2, client.getPrefix(prefix).size());
        assertTrue(client.txnPutIfAbsent(prefix + "leader", "node-1", 0));
        assertFalse(client.txnPutIfAbsent(prefix + "leader", "node-2", 0));
        assertEquals("node-1", client.get(prefix + "leader").orElseThrow());
    }

    @Test
    void watchReceivesPutAndDelete() throws Exception {
        CountDownLatch events = new CountDownLatch(2);
        List<EtcdWatchEvent.Type> types = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (var ignored = client.watch(prefix, event -> {
            types.add(event.type());
            events.countDown();
        })) {
            client.put(prefix + "watched", "value");
            client.delete(prefix + "watched");
            assertTrue(events.await(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of(EtcdWatchEvent.Type.PUT, EtcdWatchEvent.Type.DELETE), types);
    }

    @Test
    void leaseExpiresWithoutKeepAliveAndSurvivesWithIt() throws Exception {
        String expiringKey = prefix + "expiring";
        client.putWithLease(expiringKey, "value", 2);
        awaitMissing(expiringKey, Duration.ofSeconds(7));

        String keptKey = prefix + "kept";
        long keptLease = client.putWithLease(keptKey, "value", 2);
        try (var ignored = client.keepAlive(keptLease)) {
            Thread.sleep(3_000);
            assertTrue(client.get(keptKey).isPresent());
        } finally {
            client.revokeLease(keptLease);
        }
    }

    @Test
    void nodeAndWorkerClaimsAreAtomicAndShareTheLease() {
        long lease = client.grantLease(6);
        try {
            assertTrue(client.txnRegisterNodeAndWorker("lesson42-node", "{}", 42, lease));
            assertFalse(client.txnRegisterNodeAndWorker("other-node", "{}", 42, lease));
            assertFalse(client.txnRegisterNodeAndWorker("lesson42-node", "{}", 43, lease));
            assertTrue(client.get(EtcdKeys.node("lesson42-node")).isPresent());
            assertTrue(client.get(EtcdKeys.worker(42)).isPresent());
            assertTrue(client.get(EtcdKeys.node("other-node")).isEmpty());
            assertTrue(client.get(EtcdKeys.worker(43)).isEmpty());
        } finally {
            client.revokeLease(lease);
        }
        assertTrue(client.get(EtcdKeys.node("lesson42-node")).isEmpty());
        assertTrue(client.get(EtcdKeys.worker(42)).isEmpty());
    }

    @Test
    void documentedMetadataKeySpacesRoundTrip() {
        String suffix = UUID.randomUUID().toString();
        String nodeId = "node-" + suffix;
        String timeWheelId = "tw-" + suffix;
        String configName = "test-" + suffix;
        MetadataJsonCodec codec = new MetadataJsonCodec();
        long lease = client.grantLease(6);
        try {
            client.putAttachedToLease(
                    EtcdKeys.node(nodeId),
                    codec.encodeNode(new NodeMetadata("127.0.0.1", 8080, 1_234, 0)),
                    lease);
            client.putAttachedToLease(EtcdKeys.CONTROLLER, nodeId, lease);
            client.put(
                    EtcdKeys.timeWheel(timeWheelId),
                    codec.encodeTimeWheel(new TimeWheelMetadata(nodeId, "slave-" + suffix, "running")));
            client.put(EtcdKeys.config(configName), "2");

            assertTrue(client.getPrefix(EtcdKeys.NODES_PREFIX).containsKey(EtcdKeys.node(nodeId)));
            assertEquals(nodeId, client.get(EtcdKeys.CONTROLLER).orElseThrow());
            assertTrue(client.getPrefix(EtcdKeys.TIME_WHEELS_PREFIX)
                    .containsKey(EtcdKeys.timeWheel(timeWheelId)));
            assertEquals("2", client.get(EtcdKeys.config(configName)).orElseThrow());
        } finally {
            client.delete(EtcdKeys.timeWheel(timeWheelId));
            client.delete(EtcdKeys.config(configName));
            client.revokeLease(lease);
        }
    }

    private void awaitMissing(String key, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (client.get(key).isEmpty()) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(client.get(key).isEmpty(), "leased key did not expire");
    }

    private static Map<String, String> configuredEnvironment() throws IOException {
        Map<String, String> result = new HashMap<>(System.getenv());
        if (result.containsKey(EtcdClientConfig.ENDPOINTS_ENV)) {
            return result;
        }
        Path repositoryRoot = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."));
        Path runtimeEnvironment = repositoryRoot.resolve(".loop/runtime.env").normalize();
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
