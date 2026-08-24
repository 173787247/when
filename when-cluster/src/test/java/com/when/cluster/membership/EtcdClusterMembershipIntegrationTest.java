package com.when.cluster.membership;

import com.when.cluster.etcd.EtcdClientConfig;
import com.when.cluster.etcd.EtcdKeys;
import com.when.cluster.etcd.EtcdMetadataClient;
import com.when.cluster.etcd.MetadataJsonCodec;
import com.when.cluster.etcd.TimeWheelMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EtcdClusterMembershipIntegrationTest {
    private final List<EtcdClusterMembership> memberships = new ArrayList<>();
    private final List<EtcdMetadataClient> clients = new ArrayList<>();
    private Map<String, String> environment;
    private String suffix;

    @BeforeEach
    void setUp() throws IOException {
        environment = configuredEnvironment();
        assumeTrue(environment.containsKey(EtcdClientConfig.ENDPOINTS_ENV));
        suffix = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        memberships.forEach(EtcdClusterMembership::close);
        clients.forEach(EtcdMetadataClient::close);
    }

    @Test
    void registrationIsAtomicHeartbeatKeepsBothClaimsAndDeregisterReleasesThem()
            throws Exception {
        EtcdClusterMembership first = membership(3, Duration.ofMillis(500));
        EtcdClusterMembership conflict = membership(3, Duration.ofMillis(500));
        NodeInfo firstNode = node("first");
        first.registerSelf(firstNode, 701);

        assertThrows(IllegalStateException.class,
                () -> conflict.registerSelf(node("conflict"), 701));
        Thread.sleep(3_500);
        assertEquals(List.of(firstNode), first.listNodes());
        assertTrue(client().get(EtcdKeys.worker(701)).isPresent());

        first.deregisterSelf();
        await(() -> client().get(EtcdKeys.node(firstNode.nodeId())).isEmpty(), Duration.ofSeconds(3));
        assertTrue(client().get(EtcdKeys.worker(701)).isEmpty());

        conflict.registerSelf(node("replacement"), 701);
        assertEquals(701, conflict.workerId());
    }

    @Test
    void concurrentElectionHasOneWinnerAndFollowerReelectsAfterControllerExit()
            throws Exception {
        List<EtcdClusterMembership> candidates = List.of(membership(), membership(), membership());
        for (int i = 0; i < candidates.size(); i++) {
            candidates.get(i).registerSelf(node("candidate-" + i), 710 + i);
        }
        var pool = Executors.newFixedThreadPool(candidates.size());
        List<Boolean> results;
        try {
            results = pool.invokeAll(candidates.stream()
                            .<java.util.concurrent.Callable<Boolean>>map(
                                    member -> member::tryBecomeController)
                            .toList())
                    .stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception e) {
                            throw new AssertionError(e);
                        }
                    })
                    .toList();
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        String controllerId = candidates.get(0).currentController().orElseThrow();
        EtcdClusterMembership controller = candidates.stream()
                .filter(candidate -> candidate.isController()
                        && candidate.currentController().filter(controllerId::equals).isPresent())
                .findFirst()
                .orElseThrow();
        assertEquals(3, controller.controllerSnapshot().orElseThrow().nodes().size());

        controller.deregisterSelf();
        await(() -> candidates.stream()
                        .filter(candidate -> candidate != controller)
                        .filter(EtcdClusterMembership::isController)
                        .count() == 1,
                Duration.ofSeconds(5));
        assertTrue(candidates.stream()
                .filter(candidate -> candidate != controller)
                .allMatch(candidate -> candidate.currentController().isPresent()));
    }

    @Test
    void memberWatchReportsTheSamePutAndDeletePathForActiveExit() throws Exception {
        EtcdClusterMembership observer = membership();
        EtcdClusterMembership joining = membership();
        observer.registerSelf(node("observer"), 720);
        CountDownLatch events = new CountDownLatch(2);
        List<MemberEvent> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
        observer.watchMembers(event -> {
            if (event.nodeId().equals(nodeId("joining"))) {
                observed.add(event);
                events.countDown();
            }
        });

        joining.registerSelf(node("joining"), 721);
        joining.deregisterSelf();

        assertTrue(events.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(MemberEvent.Type.PUT, MemberEvent.Type.DELETE),
                observed.stream().map(MemberEvent::type).toList());
        assertTrue(observed.get(0).nodeInfo().isPresent());
        assertTrue(observed.get(1).nodeInfo().isEmpty());
    }

    @Test
    void controllerSnapshotLoadsExistingTimeWheelFactsWithoutChangingThem() {
        EtcdMetadataClient seed = client();
        String wheelId = "tw-" + suffix;
        TimeWheelMetadata placement =
                new TimeWheelMetadata(nodeId("one"), nodeId("two"), "running", "in_sync", 4);
        seed.put(EtcdKeys.timeWheel(wheelId), new MetadataJsonCodec().encodeTimeWheel(placement));
        EtcdClusterMembership candidate = membership();
        candidate.registerSelf(node("one"), 730);

        assertTrue(candidate.tryBecomeController());

        assertEquals(placement,
                candidate.controllerSnapshot().orElseThrow().timeWheels().get(wheelId));
        assertEquals(placement,
                new MetadataJsonCodec().decodeTimeWheel(
                        seed.get(EtcdKeys.timeWheel(wheelId)).orElseThrow()));
        seed.delete(EtcdKeys.timeWheel(wheelId));
    }

    private EtcdClusterMembership membership() {
        return membership(6, Duration.ofSeconds(2));
    }

    private EtcdClusterMembership membership(long ttlSeconds, Duration heartbeatInterval) {
        EtcdClusterMembership membership =
                new EtcdClusterMembership(client(), ttlSeconds, heartbeatInterval);
        memberships.add(membership);
        return membership;
    }

    private EtcdMetadataClient client() {
        EtcdMetadataClient client =
                new EtcdMetadataClient(EtcdClientConfig.fromEnvironment(environment));
        clients.add(client);
        return client;
    }

    private NodeInfo node(String name) {
        return new NodeInfo(nodeId(name), "127.0.0.1", 8_000 + memberships.size(), 1, 0);
    }

    private String nodeId(String name) {
        return name + "-" + suffix;
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
