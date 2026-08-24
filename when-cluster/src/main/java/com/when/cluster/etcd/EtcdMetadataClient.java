package com.when.cluster.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.ClientBuilder;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.support.CloseableClient;
import io.etcd.jetcd.watch.WatchEvent;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.SSLException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import com.when.observability.Metrics;
import com.when.observability.TraceAttributes;
import com.when.observability.TraceOperations;
import com.when.observability.TraceSpanKind;
import com.when.observability.WhenMetrics;

/**
 * The cluster module's sole low-level etcd entry point.
 *
 * <p>All blocking calls are bounded by the configured operation timeout. Watch and keep-alive
 * streams are explicitly closeable and must be owned by the lifecycle that starts them.</p>
 */
public final class EtcdMetadataClient implements AutoCloseable {
    private static final ByteSequence EMPTY = bytes("");

    private final Client client;
    private final KV kv;
    private final Lease lease;
    private final Watch watch;
    private final Duration timeout;
    private final Metrics metrics;
    private final TraceOperations traces;

    public EtcdMetadataClient(EtcdClientConfig config) {
        this(buildClient(config), config.operationTimeout(), Metrics.noop(), TraceOperations.noop());
    }

    public EtcdMetadataClient(EtcdClientConfig config, Metrics metrics, TraceOperations traces) {
        this(buildClient(config), config.operationTimeout(), metrics, traces);
    }

    EtcdMetadataClient(Client client, Duration timeout) {
        this(client, timeout, Metrics.noop(), TraceOperations.noop());
    }

    EtcdMetadataClient(Client client, Duration timeout, Metrics metrics, TraceOperations traces) {
        this.client = Objects.requireNonNull(client, "client");
        this.kv = client.getKVClient();
        this.lease = client.getLeaseClient();
        this.watch = client.getWatchClient();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.traces = Objects.requireNonNull(traces, "traces");
    }

    public static EtcdMetadataClient fromEnvironment() {
        return new EtcdMetadataClient(EtcdClientConfig.fromEnvironment());
    }

    public static EtcdMetadataClient fromEnvironment(Metrics metrics, TraceOperations traces) {
        return new EtcdMetadataClient(EtcdClientConfig.fromEnvironment(), metrics, traces);
    }

    public void put(String key, String value) {
        await(kv.put(bytes(required(key, "key")), bytes(required(value, "value"))), "put");
    }

    public long putWithLease(String key, String value, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be positive");
        }
        long leaseId = await(lease.grant(ttlSeconds), "grant lease").getID();
        try {
            putAttachedToLease(key, value, leaseId);
            return leaseId;
        } catch (RuntimeException e) {
            try {
                await(lease.revoke(leaseId), "revoke unused lease");
            } catch (RuntimeException revokeFailure) {
                e.addSuppressed(revokeFailure);
            }
            throw e;
        }
    }

    public void putAttachedToLease(String key, String value, long leaseId) {
        if (leaseId == 0) {
            throw new IllegalArgumentException("leaseId must not be zero");
        }
        PutOption option = PutOption.builder().withLeaseId(leaseId).build();
        await(kv.put(bytes(required(key, "key")), bytes(required(value, "value")), option), "put with lease");
    }

    public Optional<String> get(String key) {
        var response = await(kv.get(bytes(required(key, "key"))), "get");
        if (response.getKvs().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(text(response.getKvs().get(0).getValue()));
    }

    public Optional<EtcdValue> getValue(String key) {
        var response = await(kv.get(bytes(required(key, "key"))), "get value");
        if (response.getKvs().isEmpty()) {
            return Optional.empty();
        }
        KeyValue value = response.getKvs().get(0);
        return Optional.of(etcdValue(value));
    }

    public Map<String, String> getPrefix(String prefix) {
        GetOption option = GetOption.builder().isPrefix(true).build();
        var response = await(kv.get(bytes(required(prefix, "prefix")), option), "get prefix");
        Map<String, String> result = new LinkedHashMap<>();
        for (KeyValue keyValue : response.getKvs()) {
            result.put(text(keyValue.getKey()), text(keyValue.getValue()));
        }
        return Map.copyOf(result);
    }

    /** Reads a whole prefix in one linearizable request and preserves its header revision. */
    public EtcdSnapshot getPrefixSnapshot(String prefix) {
        GetOption option = GetOption.builder().isPrefix(true).build();
        var response = await(
                kv.get(bytes(required(prefix, "prefix")), option),
                "get prefix snapshot");
        Map<String, EtcdValue> result = new LinkedHashMap<>();
        for (KeyValue keyValue : response.getKvs()) {
            result.put(text(keyValue.getKey()), etcdValue(keyValue));
        }
        return new EtcdSnapshot(response.getHeader().getRevision(), result);
    }

    public boolean delete(String key) {
        return await(kv.delete(bytes(required(key, "key"))), "delete").getDeleted() > 0;
    }

    public WatchHandle watch(String prefix, Consumer<EtcdWatchEvent> handler) {
        return watchPrefix(prefix, 0, handler, ignored -> { });
    }

    public WatchHandle watchPrefix(
            String prefix,
            long startRevision,
            Consumer<EtcdWatchEvent> handler,
            Consumer<Throwable> errorHandler) {
        return watchInternal(prefix, true, startRevision, handler, errorHandler);
    }

    public WatchHandle watchKey(
            String key,
            long startRevision,
            Consumer<EtcdWatchEvent> handler,
            Consumer<Throwable> errorHandler) {
        return watchInternal(key, false, startRevision, handler, errorHandler);
    }

    private WatchHandle watchInternal(
            String key,
            boolean prefix,
            long startRevision,
            Consumer<EtcdWatchEvent> handler,
            Consumer<Throwable> errorHandler) {
        required(key, "key");
        if (startRevision < 0) {
            throw new IllegalArgumentException("startRevision must not be negative");
        }
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(errorHandler, "errorHandler");
        WatchOption.Builder options = WatchOption.builder();
        if (prefix) {
            options.isPrefix(true);
        }
        if (startRevision > 0) {
            options.withRevision(startRevision);
        }
        Watch.Watcher watcher = watch.watch(bytes(key), options.build(), Watch.listener(response -> {
            for (WatchEvent event : response.getEvents()) {
                EtcdWatchEvent.Type type = event.getEventType() == WatchEvent.EventType.DELETE
                        ? EtcdWatchEvent.Type.DELETE
                        : EtcdWatchEvent.Type.PUT;
                KeyValue keyValue = event.getKeyValue();
                handler.accept(new EtcdWatchEvent(
                        type,
                        text(keyValue.getKey()),
                        text(keyValue.getValue()),
                        keyValue.getModRevision()));
            }
        }, errorHandler));
        return watcher::close;
    }

    public KeepAliveHandle keepAlive(long leaseId) {
        if (leaseId == 0) {
            throw new IllegalArgumentException("leaseId must not be zero");
        }
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        CloseableClient stream = lease.keepAlive(leaseId, new StreamObserver<>() {
            @Override
            public void onNext(LeaseKeepAliveResponse value) {
                // Receiving a response is sufficient; lifecycle policy belongs to lesson 43.
            }

            @Override
            public void onError(Throwable error) {
                failure.complete(error);
            }

            @Override
            public void onCompleted() {
                failure.complete(null);
            }
        });
        return new KeepAliveHandle(stream, failure);
    }

    public void keepAliveOnce(long leaseId) {
        await(lease.keepAliveOnce(leaseId), "keep lease alive once");
    }

    public void revokeLease(long leaseId) {
        await(lease.revoke(leaseId), "revoke lease");
    }

    public boolean txnPutIfAbsent(String key, String value, long leaseId) {
        ByteSequence encodedKey = bytes(required(key, "key"));
        PutOption putOption = putOption(leaseId);
        return await(kv.txn()
                        .If(new Cmp(encodedKey, Cmp.Op.EQUAL, CmpTarget.version(0)))
                        .Then(Op.put(encodedKey, bytes(required(value, "value")), putOption))
                        .commit(),
                "transactional put if absent").isSucceeded();
    }

    /** Replaces a value only when it still equals the caller's observed value. */
    public boolean txnPutIfValue(String key, String expectedValue, String newValue) {
        return txnPutIfValue(key, expectedValue, newValue, 0);
    }

    /** Replaces a value under CAS while preserving the caller-owned retention lease. */
    public boolean txnPutIfValue(
            String key, String expectedValue, String newValue, long leaseId) {
        ByteSequence encodedKey = bytes(required(key, "key"));
        return await(kv.txn()
                        .If(new Cmp(
                                encodedKey,
                                Cmp.Op.EQUAL,
                                CmpTarget.value(bytes(required(expectedValue, "expectedValue")))))
                        .Then(Op.put(
                                encodedKey,
                                bytes(required(newValue, "newValue")),
                                putOption(leaseId)))
                        .commit(),
                "transactional compare and put").isSucceeded();
    }

    /**
     * Commits a Controller decision only while the elected Controller term and target record are
     * still exactly the values observed by the decision maker. The progress update is part of the
     * same transaction, so a successor can resume without an in-memory handoff.
     */
    public EtcdTxnResult txnControllerAssignment(
            String controllerNodeId,
            long controllerTerm,
            String targetKey,
            long expectedModRevision,
            String expectedValue,
            String replacementValue,
            String progressValue) {
        required(controllerNodeId, "controllerNodeId");
        required(targetKey, "targetKey");
        required(replacementValue, "replacementValue");
        required(progressValue, "progressValue");
        if (controllerTerm <= 0) {
            throw new IllegalArgumentException("controllerTerm must be positive");
        }
        if (expectedModRevision < 0) {
            throw new IllegalArgumentException("expectedModRevision must not be negative");
        }
        ByteSequence controllerKey = bytes(EtcdKeys.CONTROLLER);
        ByteSequence assignmentKey = bytes(targetKey);
        var transaction = kv.txn().If(
                new Cmp(controllerKey, Cmp.Op.EQUAL, CmpTarget.modRevision(controllerTerm)),
                new Cmp(controllerKey, Cmp.Op.EQUAL, CmpTarget.value(bytes(controllerNodeId))));
        if (expectedModRevision == 0) {
            transaction.If(new Cmp(assignmentKey, Cmp.Op.EQUAL, CmpTarget.version(0)));
        } else {
            transaction.If(
                    new Cmp(
                            assignmentKey,
                            Cmp.Op.EQUAL,
                            CmpTarget.modRevision(expectedModRevision)),
                    new Cmp(
                            assignmentKey,
                            Cmp.Op.EQUAL,
                            CmpTarget.value(bytes(required(expectedValue, "expectedValue")))));
        }
        var response = await(transaction.Then(
                        Op.put(assignmentKey, bytes(replacementValue), PutOption.DEFAULT),
                        Op.put(
                                bytes(EtcdKeys.CONTROLLER_PROGRESS),
                                bytes(progressValue),
                                PutOption.DEFAULT))
                .commit(), "transactional Controller assignment");
        return new EtcdTxnResult(response.isSucceeded(), response.getHeader().getRevision());
    }

    /** Writes replay progress only while the caller still owns the exact election term. */
    public boolean txnControllerProgress(
            String controllerNodeId,
            long controllerTerm,
            String progressValue) {
        required(controllerNodeId, "controllerNodeId");
        required(progressValue, "progressValue");
        if (controllerTerm <= 0) {
            throw new IllegalArgumentException("controllerTerm must be positive");
        }
        ByteSequence controllerKey = bytes(EtcdKeys.CONTROLLER);
        return await(kv.txn()
                        .If(
                                new Cmp(
                                        controllerKey,
                                        Cmp.Op.EQUAL,
                                        CmpTarget.modRevision(controllerTerm)),
                                new Cmp(
                                        controllerKey,
                                        Cmp.Op.EQUAL,
                                        CmpTarget.value(bytes(controllerNodeId))))
                        .Then(Op.put(
                                bytes(EtcdKeys.CONTROLLER_PROGRESS),
                                bytes(progressValue),
                                PutOption.DEFAULT))
                        .commit(),
                "transactional Controller progress").isSucceeded();
    }

    public boolean txnRegisterNodeAndWorker(
            String nodeId,
            String nodeValue,
            int workerId,
            long leaseId) {
        if (leaseId == 0) {
            throw new IllegalArgumentException("leaseId must not be zero");
        }
        ByteSequence nodeKey = bytes(EtcdKeys.node(nodeId));
        ByteSequence workerKey = bytes(EtcdKeys.worker(workerId));
        PutOption option = putOption(leaseId);
        return await(kv.txn()
                        .If(
                                new Cmp(nodeKey, Cmp.Op.EQUAL, CmpTarget.version(0)),
                                new Cmp(workerKey, Cmp.Op.EQUAL, CmpTarget.version(0)))
                        .Then(
                                Op.put(nodeKey, bytes(required(nodeValue, "nodeValue")), option),
                                Op.put(workerKey, bytes(nodeId), option))
                        .commit(),
                "transactional node registration").isSucceeded();
    }

    public long grantLease(long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be positive");
        }
        return await(lease.grant(ttlSeconds), "grant lease").getID();
    }

    @Override
    public void close() {
        client.close();
    }

    /** Bounded linearizable dependency probe for readiness. */
    public boolean healthCheck() {
        try {
            get(EtcdKeys.CONTROLLER);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static Client buildClient(EtcdClientConfig config) {
        Objects.requireNonNull(config, "config");
        ClientBuilder builder = Client.builder().endpoints(config.endpoints());
        config.username().ifPresent(username -> builder
                .user(bytes(username))
                .password(bytes(config.password())));
        if (config.caCertificate().isPresent() || config.clientCertificate().isPresent()) {
            try {
                SslContextBuilder ssl = SslContextBuilder.forClient();
                config.caCertificate().ifPresent(path -> ssl.trustManager(path.toFile()));
                if (config.clientCertificate().isPresent()) {
                    ssl.keyManager(
                            config.clientCertificate().orElseThrow().toFile(),
                            config.clientKey().orElseThrow().toFile());
                }
                builder.sslContext(ssl.build());
            } catch (SSLException e) {
                throw new IllegalArgumentException("invalid etcd TLS configuration", e);
            }
        }
        return builder.build();
    }

    private PutOption putOption(long leaseId) {
        return leaseId == 0
                ? PutOption.DEFAULT
                : PutOption.builder().withLeaseId(leaseId).build();
    }

    private <T> T await(CompletableFuture<T> future, String operation) {
        String metricOperation = operation.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        long started = System.nanoTime();
        try {
            T result = traces.inSpan(
                    "when.metadata",
                    TraceSpanKind.CLIENT,
                    TraceAttributes.of("db.system", "etcd", "db.operation.name", metricOperation),
                    () -> awaitFuture(future, operation));
            metrics.incr(
                    WhenMetrics.ETCD_OPERATIONS,
                    "operation", metricOperation,
                    "status", "success");
            return result;
        } catch (RuntimeException failure) {
            metrics.incr(
                    WhenMetrics.ETCD_OPERATIONS,
                    "operation", metricOperation,
                    "status", "failure");
            throw failure;
        } finally {
            metrics.observe(
                    WhenMetrics.ETCD_OPERATION_DURATION,
                    Math.max(0d, (System.nanoTime() - started) / 1_000_000_000d),
                    "operation", metricOperation);
        }
    }

    private <T> T awaitFuture(CompletableFuture<T> future, String operation) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EtcdClientException(operation, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EtcdClientException(operation, e);
        }
    }

    private static ByteSequence bytes(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }

    private static String text(ByteSequence value) {
        return value == null || value.equals(EMPTY) ? "" : value.toString(StandardCharsets.UTF_8);
    }

    private static EtcdValue etcdValue(KeyValue value) {
        return new EtcdValue(
                text(value.getValue()), value.getCreateRevision(), value.getModRevision());
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @FunctionalInterface
    public interface WatchHandle extends AutoCloseable {
        @Override
        void close();
    }

    public static final class KeepAliveHandle implements AutoCloseable {
        private final CloseableClient stream;
        private final CompletableFuture<Throwable> failure;

        private KeepAliveHandle(CloseableClient stream, CompletableFuture<Throwable> failure) {
            this.stream = stream;
            this.failure = failure;
        }

        public Optional<Throwable> failure() {
            return failure.isDone() ? Optional.ofNullable(failure.getNow(null)) : Optional.empty();
        }

        @Override
        public void close() {
            stream.close();
        }
    }
}
